package com.asl.backend.dictionary.adapter.in.web;

import com.asl.backend.dictionary.application.api.DictionaryApi;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

/** Public link fallback exposes introduction only; membership always stays behind its API policy. */
@RestController
public class CollectionLandingController {
    private final DictionaryApi api;
    public CollectionLandingController(DictionaryApi api) { this.api = api; }

    @GetMapping(value="/collections/{id}",produces=MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> landing(@PathVariable String id) {
        try {
            if (!id.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return page(404,"Collection unavailable","");
            var collection = api.collection(UUID.fromString(id));
            String name = HtmlUtils.htmlEscape(collection.get("name").toString());
            String description = HtmlUtils.htmlEscape(collection.get("description").toString());
            String canonical = HtmlUtils.htmlEscape(collection.get("shareUrl").toString());
            return page(200,name,"<link rel=\"canonical\" href=\""+canonical+"\"><p>"+description+"</p><p>Open this collection in the ASL app to explore its signs.</p>"
                + (Boolean.TRUE.equals(collection.get("isPremium")) ? "<p>A verified Premium subscription is required to view this collection’s signs.</p>" : ""));
        } catch (ApplicationFailure failure) {
            if (failure.kind() == ApplicationFailure.Kind.NOT_FOUND) return page(404,"Collection unavailable","");
            throw failure;
        }
    }
    private ResponseEntity<String> page(int status,String title,String body) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).header("Cache-Control","no-store")
            .header("Content-Security-Policy","default-src 'none'; frame-ancestors 'none'; base-uri 'none'")
            .body("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>"+title+"</title><main><h1>"+title+"</h1>"+body+"</main></html>");
    }
}
