package com.example.deploymentconsole.service;

import com.example.deploymentconsole.config.AppProperties;
import com.example.deploymentconsole.model.*;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DeploymentService {
    private static final Logger log = LoggerFactory.getLogger(DeploymentService.class);
    private final ChecksumService checksumService;
    private final ScriptScanner scanner;
    private final AppProperties props;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final HistoryService historyService;
    private final DeploymentLockService lockService;
    private final Map<String, DeploymentState> jobs = new ConcurrentHashMap<>();

    public DeploymentService(ScriptScanner scanner, AppProperties props, HistoryService historyService,
                             DeploymentLockService lockService, ChecksumService checksumService) {
        this.checksumService=checksumService; this.scanner=scanner; this.props=props; this.historyService=historyService; this.lockService=lockService;
    }

    /** Close out deployments left RUNNING by a previous process so they don't appear active forever. */
    @PostConstruct
    void recoverInterrupted() { historyService.markInterrupted(); }

    public List<ScriptInfo> scan(String folder) throws Exception { return scanner.scan(folder); }

    /** Scan and flag scripts whose content differs from their last successful execution in {@code environment}. */
    public List<ScriptInfo> scan(String folder, String environment) throws Exception {
        List<ScriptInfo> scripts=scanner.scan(folder);
        if(environment==null||environment.isBlank()) return scripts;
        return withChecksums(folder,scripts,historyService.getPreviousChecksums(environment));
    }

    /** Computes each script's checksum and marks it modified if it differs from {@code previous} (path -> checksum). */
    private List<ScriptInfo> withChecksums(String folder,List<ScriptInfo> scripts,Map<String,String> previous) throws java.io.IOException {
        List<ScriptInfo> out=new ArrayList<>();
        for(ScriptInfo x:scripts){
            String checksum=checksumService.computeChecksum(scanner.resolve(folder,x.path()));
            String prev=previous.get(x.path());
            boolean modified=prev!=null&&!prev.equals(checksum);
            out.add(new ScriptInfo(x.order(),x.path(),x.filename(),x.sequence(),x.status(),x.durationMs(),x.error(),
                    checksum,modified));
        }
        return out;
    }

    public String start(DeploymentRequest req) throws Exception { return start(req, null); }

    /** Starts a deployment on behalf of {@code deployedBy} (username recorded in the history for auditing). */
    public String start(DeploymentRequest req, String deployedBy) throws Exception {
        var db = props.getDatabases().get(req.environment().toLowerCase(Locale.ROOT));
        if(db==null) throw new IllegalArgumentException("Unknown environment: "+req.environment());

        List<ScriptInfo> scripts=scanner.scan(req.folder());
        if(scripts.isEmpty()) throw new IllegalArgumentException("No SQL scripts found.");

        // Checksum validation: compare each script with its last successful execution in this environment.
        Map<String,String> previous=historyService.getPreviousChecksums(req.environment());
        scripts=withChecksums(req.folder(),scripts,previous);
        List<String> modified=scripts.stream().filter(ScriptInfo::modified).map(ScriptInfo::path).toList();
        for(String path:modified)
            log.warn("Script {} has changed since last execution in {}",path,req.environment());
        if(!modified.isEmpty()&&props.getExecution().isRequireConfirmationForModified()
                &&!Boolean.TRUE.equals(req.confirmModified()))
            throw new ScriptsModifiedException(modified);   // before taking the lock: nothing to clean up

        String id=UUID.randomUUID().toString();

        // One deployment per environment across ALL instances: take the DB lock before doing anything else.
        if(!lockService.acquireLock(req.environment(), id, props.getExecution().getLockTimeoutMinutes()))
            throw new DeploymentLockedException(req.environment(), lockService.getLockInfo(req.environment()));

        try {
            DeploymentState state=new DeploymentState(id,req.environment(),req.folder(),scripts,
                    "all".equalsIgnoreCase(req.commitMode()) ? "all" : "script");
            state.startedAt=Instant.now();
            state.deployedBy=deployedBy;
            state.previousChecksums=previous;
            jobs.put(id,state);
            historyService.save(state);
            executor.submit(() -> execute(state, db));
            return id;
        } catch(RuntimeException e) {
            // Never leave the environment locked if the job could not be handed to the executor.
            jobs.remove(id);
            lockService.releaseLock(req.environment(), id);
            throw e;
        }
    }

    public DeploymentState get(String id){
        DeploymentState s=jobs.get(id);
        if(s==null) s=historyService.load(id);   // finished before a restart
        if(s==null) throw new NoSuchElementException("Deployment not found");
        return s;
    }

    /** Oldest deployment that is still queued or running, if any. */
    public Optional<String> activeId(){
        return jobs.values().stream()
                .filter(s -> "PENDING".equals(s.status) || "RUNNING".equals(s.status))
                .min(Comparator.comparing(s -> s.startedAt))
                .map(s -> s.id);
    }

    private void execute(DeploymentState state, AppProperties.Database db) {
        state.status="RUNNING"; state.startedAt=Instant.now();
        log.info("AUDIT deploy-executing user={} environment={} deploymentId={} scripts={}",
                state.deployedBy, state.environment, state.id, state.scripts.size());
        try(Connection c=DriverManager.getConnection(db.getUrl(),db.getUsername(),db.getPassword())) {
            c.setAutoCommit(false);
            for(int i=0;i<state.scripts.size();i++){
                ScriptInfo info=state.scripts.get(i);
                state.current=i;
                long start=System.currentTimeMillis();
                try{
                    Path file=scanner.resolve(state.folder,info.path());
                    // Read once: the bytes that are hashed are exactly the bytes that get executed.
                    byte[] content=Files.readAllBytes(file);
                    String checksum=checksumService.computeChecksum(content);
                    String prev=state.previousChecksums.get(info.path());
                    boolean modified=prev!=null&&!prev.equals(checksum);
                    setChecksum(state,i,checksum,modified);
                    update(state,i,ScriptStatus.RUNNING,null,null);   // persists the checksum with the progress row
                    // The script was checksummed (and possibly confirmed) at start; refuse if it changed since.
                    if(info.checksum()!=null&&!info.checksum().equals(checksum))
                        throw new IllegalStateException("Script changed after the deployment started (expected checksum "
                                +info.checksum()+", found "+checksum+"). Not executed.");
                    String sql=new String(content, StandardCharsets.UTF_8);
                    List<String> commands=SqlSplitter.split(sql);
                    try(Statement st=c.createStatement()){
                        st.setQueryTimeout(props.getExecution().getScriptTimeoutSeconds());
                        for(String command:commands) st.execute(command);
                    }
                    long duration=System.currentTimeMillis()-start;
                    if("script".equals(state.commitMode)) c.commit();
                    update(state,i,ScriptStatus.SUCCESS,duration,null);
                } catch(Exception e) {
                    c.rollback();
                    long duration=System.currentTimeMillis()-start;
                    update(state,i,ScriptStatus.FAILED,duration,e.getMessage());
                    skipPending(state);
                    state.status="FAILED";
                    state.completedAt=Instant.now();
                    return;
                }
            }
            if("all".equals(state.commitMode)) c.commit();
            state.status="SUCCESS";
        } catch(Exception e) {
            skipPending(state);
            state.status="FAILED";
            state.error=e.getMessage();
        } finally {
            state.completedAt=Instant.now();
            log.info("AUDIT deploy-finished user={} environment={} deploymentId={} status={}",
                    state.deployedBy, state.environment, state.id, state.status);
            try { historyService.save(state); }
            finally { lockService.releaseLock(state.environment, state.id); }   // always free the environment
        }
    }

    private void update(DeploymentState s,int index,ScriptStatus status,Long duration,String error){
        set(s,index,status,duration,error);
        historyService.saveProgress(s,index);
    }

    private void set(DeploymentState s,int index,ScriptStatus status,Long duration,String error){
        ScriptInfo old=s.scripts.get(index);
        s.scripts.set(index,new ScriptInfo(old.order(),old.path(),old.filename(),old.sequence(),
                status,duration,error,old.checksum(),old.modified()));
    }

    private void setChecksum(DeploymentState s,int index,String checksum,boolean modified){
        ScriptInfo old=s.scripts.get(index);
        s.scripts.set(index,new ScriptInfo(old.order(),old.path(),old.filename(),old.sequence(),
                old.status(),old.durationMs(),old.error(),checksum,modified));
    }

    /** Scripts that never ran because the deployment stopped. Persisted by the final save(). */
    private void skipPending(DeploymentState s){
        for(int i=0;i<s.scripts.size();i++)
            if(s.scripts.get(i).status()==ScriptStatus.PENDING) set(s,i,ScriptStatus.SKIPPED,null,null);
    }

    public static class DeploymentState {
        public final String id, environment, folder, commitMode;
        public final List<ScriptInfo> scripts;
        public volatile String status="PENDING";
        public volatile int current=-1;
        public volatile String error;
        public volatile Instant startedAt, completedAt;
        public volatile String deployedBy;   // username that started the deployment (audit trail)
        volatile Map<String,String> previousChecksums=Map.of();   // path -> last successful checksum (not serialized)

        DeploymentState(String id,String environment,String folder,List<ScriptInfo> scripts,String commitMode){
            this.id=id;this.environment=environment;this.folder=folder;
            this.scripts=new CopyOnWriteArrayList<>(scripts);this.commitMode=commitMode;
        }
    }
}
