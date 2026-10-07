package com.example.deploymentconsole.service;

import java.util.*;

public final class SqlSplitter {
    private SqlSplitter() {}

    public static List<String> split(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean single=false, dbl=false, line=false, block=false;
        String dollar=null;

        for (int i=0;i<sql.length();i++) {
            char c=sql.charAt(i);
            char n=i+1<sql.length()?sql.charAt(i+1):'\0';

            if (line) { cur.append(c); if(c=='\n') line=false; continue; }
            if (block) {
                cur.append(c);
                if(c=='*' && n=='/') { cur.append(n); i++; block=false; }
                continue;
            }
            if (dollar!=null) {
                if(sql.startsWith(dollar,i)) { cur.append(dollar); i+=dollar.length()-1; dollar=null; }
                else cur.append(c);
                continue;
            }
            if(!single&&!dbl&&c=='-'&&n=='-') { cur.append(c).append(n); i++; line=true; continue; }
            if(!single&&!dbl&&c=='/'&&n=='*') { cur.append(c).append(n); i++; block=true; continue; }
            if(!single&&!dbl&&c=='$') {
                int e=sql.indexOf('$',i+1);
                if(e>i) {
                    String tag=sql.substring(i,e+1);
                    if(tag.matches("\\$\\$|\\$[A-Za-z_][A-Za-z0-9_]*\\$")) {
                        dollar=tag; cur.append(tag); i=e; continue;
                    }
                }
            }
            if(c=='\''&&!dbl) {
                cur.append(c);
                if(single&&n=='\''){cur.append(n);i++;} else single=!single;
                continue;
            }
            if(c=='"'&&!single) {
                cur.append(c);
                if(dbl&&n=='"'){cur.append(n);i++;} else dbl=!dbl;
                continue;
            }
            if(c==';'&&!single&&!dbl){add(out,cur);cur.setLength(0);continue;}
            cur.append(c);
        }
        add(out,cur);
        return out;
    }

    private static void add(List<String> out,StringBuilder b){
        String s=b.toString().trim();
        if(!s.isBlank()) out.add(s);
    }
}
