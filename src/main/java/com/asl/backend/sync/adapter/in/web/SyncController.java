package com.asl.backend.sync.adapter.in.web;
import com.asl.backend.sync.application.api.SyncApi;
import com.asl.backend.identity.application.api.IdentityApi;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public final class SyncController {
    private final SyncApi api; private final IdentityApi identity;
    public SyncController(SyncApi api,IdentityApi identity) { this.api=api; this.identity=identity; }
    @GetMapping("/api/v1/sync/snapshot") public Map<String,Object> snapshot(@RequestHeader(value="Authorization",required=false) String authorization,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit) { return api.snapshot(identity.requireAccount(authorization).id(),cursor,limit); }
    @GetMapping("/api/v1/sync/changes") public Map<String,Object> changes(@RequestHeader(value="Authorization",required=false) String authorization,@RequestParam String since,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit) { return api.changes(identity.requireAccount(authorization).id(),since,cursor,limit); }
    @PostMapping("/api/v1/sync/mutations") public Map<String,Object> push(@RequestHeader(value="Authorization",required=false) String authorization,@RequestBody Map<String,Object> batch) { return api.push(identity.requireAccount(authorization).id(),batch); }
    @GetMapping("/api/v1/me/stats") public Map<String,Object> stats(@RequestHeader(value="Authorization",required=false) String authorization) { return api.stats(identity.requireAccount(authorization).id()); }
}
