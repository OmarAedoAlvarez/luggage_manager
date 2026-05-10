package com.tasf.backend.controller;

import com.tasf.backend.domain.Envio;
import com.tasf.backend.service.EnvioUploadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/upload")
public class UploadController {

    private final EnvioUploadService envioUploadService;

    public UploadController(EnvioUploadService envioUploadService) {
        this.envioUploadService = envioUploadService;
    }

    @PostMapping("/envios")
    public ResponseEntity<Map<String, Object>> uploadEnvios(@RequestParam("file") MultipartFile file) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<Envio> nuevos = envioUploadService.processUpload(file);
            response.put("status", "SUCCESS");
            response.put("message", "File processed successfully");
            response.put("count", nuevos.size());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (IOException e) {
            response.put("status", "ERROR");
            response.put("message", "Failed to read file: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }
}
