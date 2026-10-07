package com.example.deploymentconsole.controller;

import com.example.deploymentconsole.config.AppProperties;
import com.example.deploymentconsole.config.RequireAuth;
import com.example.deploymentconsole.model.*;
import com.example.deploymentconsole.service.DeploymentLockService;
import com.example.deploymentconsole.service.DeploymentLockedException;
import com.example.deploymentconsole.service.DeploymentService;
import com.example.deploymentconsole.service.ScriptsModifiedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api")
@RequireAuth   // every endpoint needs a logged-in user; executing a deployment additionally needs ADMIN or DEPLOYER
public class DeploymentController {
    private static final Logger log = LoggerFactory.getLogger(DeploymentController.class);
    private final DeploymentService service;
    private final DeploymentLockService lockService;
    private final AppProperties props;
    private final List<SseEmitter> emitters=new CopyOnWriteArrayList<>();

    public DeploymentController(DeploymentService service,DeploymentLockService lockService,AppProperties props){
        this.service=service;this.lockService=lockService;this.props=props;
    }

    @GetMapping("/environments")
    public Object environments(){
        return props.getDatabases().values().stream()
                .map(d -> Map.of("key", d.getName().toLowerCase(Locale.ROOT),
                                 "name", d.getName(),
                                 "url", d.getUrl()))
                .toList();
    }

    @PostMapping("/scan")
    public List<ScriptInfo> scan(@RequestParam String folder,
                                 @RequestParam(required=false) String environment) throws Exception {
        return service.scan(folder,environment);
    }

    @PostMapping("/deploy")
    @RequireAuth(roles={Role.ADMIN, Role.DEPLOYER})
    public ResponseEntity<Map<String,Object>> deploy(@Valid @RequestBody DeploymentRequest req,
                                                     HttpServletRequest request) throws Exception {
        String username=AuthenticatedUser.from(request).username();
        // Audit trail: every deployment attempt is logged with the user, and the user is stored in deployment_history.
        log.info("AUDIT deploy-requested user={} environment={} folder={} commitMode={} confirmModified={}",
                username, req.environment(), req.folder(), req.commitMode(), req.confirmModified());
        try {
            String id=service.start(req, username);
            log.info("AUDIT deploy-started user={} environment={} deploymentId={}", username, req.environment(), id);
            return ResponseEntity.ok(Map.of("id",id));
        } catch(ScriptsModifiedException e) {
            log.info("AUDIT deploy-blocked user={} environment={} reason=SCRIPTS_MODIFIED", username, req.environment());
            Map<String,Object> body=new LinkedHashMap<>();
            body.put("error","SCRIPTS_MODIFIED");
            body.put("message",e.getMessage());
            body.put("modifiedScripts",e.getModifiedScripts());
            return ResponseEntity.status(409).body(body);
        } catch(DeploymentLockedException e) {
            log.info("AUDIT deploy-blocked user={} environment={} reason=DEPLOYMENT_LOCKED", username, req.environment());
            Map<String,Object> body=new LinkedHashMap<>();
            body.put("error","DEPLOYMENT_LOCKED");
            body.put("message",e.getMessage());
            body.put("environment",e.getEnvironment());
            body.put("lock",e.getLockInfo());   // holder deployment id, lockedAt, lockedUntil, remainingSeconds (null if just released)
            return ResponseEntity.status(409).body(body);
        }
    }

    /** Who (if anyone) currently holds the deployment lock for an environment. */
    @GetMapping("/deploy/{environment}/lock-status")
    public Map<String,Object> lockStatus(@PathVariable String environment){
        if(props.getDatabases().get(environment.toLowerCase(Locale.ROOT))==null)
            throw new NoSuchElementException("Unknown environment: "+environment);
        DeploymentLockService.LockInfo info=lockService.getLockInfo(environment);
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("environment",environment.toLowerCase(Locale.ROOT));
        body.put("locked",info!=null);
        body.put("lock",info);
        return body;
    }

    /** The deployment currently queued/running, so a refreshed page (or a second browser) can re-attach. */
    @GetMapping("/deploy/active")
    public ResponseEntity<Map<String,String>> active(){
        return service.activeId()
                .map(id -> ResponseEntity.ok(Map.of("id", id)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<String> notFound(NoSuchElementException e){
        return ResponseEntity.status(404).body(e.getMessage());
    }

    @GetMapping("/deploy/{id}")
    public DeploymentService.DeploymentState status(@PathVariable String id){
        return service.get(id);
    }

    @GetMapping(value="/deploy/{id}/events", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) throws Exception {
        SseEmitter emitter=new SseEmitter(0L);
        AtomicBoolean closed=new AtomicBoolean(false);   // set when the browser goes away (e.g. page refresh)
        emitter.onCompletion(()->closed.set(true));
        emitter.onTimeout(()->closed.set(true));
        emitter.onError(e->closed.set(true));
        emitters.add(emitter);
        var thread=new Thread(()->{
            try{
                while(!closed.get()){
                    var s=service.get(id);
                    emitter.send(SseEmitter.event().name("status").data(s));
                    if(Set.of("SUCCESS","FAILED").contains(s.status)){ emitter.complete(); break; }
                    Thread.sleep(500);
                }
            }catch(Exception e){
                if(!closed.get()) { try{ emitter.completeWithError(e); }catch(Exception ignored){} }
            }finally{ emitters.remove(emitter); }
        });
        thread.setDaemon(true); thread.start();
        return emitter;
    }
}
