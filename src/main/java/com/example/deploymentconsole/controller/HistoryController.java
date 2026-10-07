package com.example.deploymentconsole.controller;

import com.example.deploymentconsole.config.RequireAuth;
import com.example.deploymentconsole.service.HistoryService;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/history")
@RequireAuth
public class HistoryController {
    private final HistoryService service;
    public HistoryController(HistoryService service){this.service=service;}
    @GetMapping public List<Map<String,Object>> list(){return service.list();}
}
