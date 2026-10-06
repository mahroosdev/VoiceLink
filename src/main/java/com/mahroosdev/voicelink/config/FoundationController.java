package com.mahroosdev.voicelink.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class FoundationController {

    @GetMapping("/")
    public String foundation() {
        return "foundation";
    }
}
