package com.example.deploymentconsole.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
    @GetMapping("/")
    public String index(){ return "index"; }

    /** Login page (public; the API calls it makes are what is protected). */
    @GetMapping("/login")
    public String login(){ return "login"; }
}
