package com.asl.backend.dictionary.adapter.in.web;
import com.asl.backend.dictionary.application.api.DictionaryApi;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class DictionaryController {
    private final DictionaryApi api;
    public DictionaryController(DictionaryApi api) { this.api = api; }
    @GetMapping("/api/v1/signs") public Map<String,Object> signs(@RequestParam(required=false) String cursor,@RequestParam(required=false) String limit) {
        return api.page("signs",null,null,"SIGN",cursor,limit(limit),null);
    }
    @GetMapping("/api/v1/signs/{id}") public Map<String,Object> sign(@PathVariable String id) { return api.sign(uuid(id)); }
    @GetMapping("/api/v1/collections") public Map<String,Object> collections(@RequestParam(required=false) String cursor,@RequestParam(required=false) String limit) {
        return api.page("collections",null,null,"COLLECTION",cursor,limit(limit),null);
    }
    @GetMapping("/api/v1/collections/{id}") public Map<String,Object> collection(@PathVariable String id) { return api.collection(uuid(id)); }
    @GetMapping("/api/v1/collections/{id}/signs") public Map<String,Object> membership(@PathVariable String id,@RequestParam(required=false) String cursor,
            @RequestParam(required=false) String limit,@RequestHeader(value="Authorization",required=false) String authorization) {
        return api.page("membership",uuid(id),null,"SIGN",cursor,limit(limit),authorization);
    }
    @GetMapping("/api/v1/search") public Map<String,Object> search(@RequestParam String q,@RequestParam(defaultValue="ALL") String type,
            @RequestParam(required=false) String cursor,@RequestParam(required=false) String limit) {
        return api.page("search",null,q,type,cursor,limit(limit),null);
    }
    private static int limit(String value) {
        if (value == null) return 20;
        if (!value.matches("[0-9]{1,3}")) throw bad();
        int n = Integer.parseInt(value); if (n < 1 || n > 100) throw bad(); return n;
    }
    private static UUID uuid(String value) {
        if (!value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw bad();
        return UUID.fromString(value);
    }
    private static ApplicationFailure bad() { return new ApplicationFailure(ApplicationFailure.Kind.BAD_REQUEST,"BAD_REQUEST","Invalid dictionary parameters"); }
}
