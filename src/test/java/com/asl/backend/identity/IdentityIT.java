package com.asl.backend.identity;

import com.asl.backend.identity.application.api.IdentityApi;
import com.asl.backend.identity.application.port.out.IdTokenVerifier;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL/HTTP; only the external Firebase verifier is replaced in this test context. */
@ActiveProfiles("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"spring.config.import=", "asl.identity.rate-limit.requests=1000"})
@Import(IdentityIT.VerifierFixture.class)
class IdentityIT {
    @TestConfiguration(proxyBeanMethods=false)
    static class VerifierFixture {
        @Bean @Primary IdTokenVerifier testFirebaseVerifier() {
            return token -> {
                if (token.startsWith("revoked:")) throw new ApplicationFailure(ApplicationFailure.Kind.UNAUTHENTICATED,"UNAUTHORIZED","Revoked test credential");
                if (token.startsWith("unavailable:")) throw new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE,"SERVICE_UNAVAILABLE","External verifier unavailable");
                String[] parts=token.split(":",-1);
                if(parts.length!=2) throw new ApplicationFailure(ApplicationFailure.Kind.UNAUTHENTICATED,"UNAUTHORIZED","Invalid test credential");
                return new IdTokenVerifier.Identity(parts[1],parts[0].equals("apple")?null:"same@example.org","Initial name",
                    parts[0].equals("password")?"password":parts[0].equals("apple")?"apple.com":"google.com");
            };
        }
    }
    @Autowired IdentityApi identity;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    final JsonMapper json=JsonMapper.builder().build();
    UUID terms=UUID.randomUUID(), privacy=UUID.randomUUID();
    @BeforeAll void legalFixtures() {
        for(var entry:Map.of(terms,"TERMS",privacy,"PRIVACY").entrySet())
            jdbc.update("insert into asl.legal_documents(id,document_type,version,public_url,published_at) values (?,?,?,'https://legal.example.org/test',now())",
                entry.getKey(),entry.getValue(),UUID.randomUUID().toString());
    }
    List<UUID> consent() { return List.of(terms,privacy); }
    String token() { return "google:"+UUID.randomUUID(); }
    @Test void bootstrapConcurrencyHasOneAccountStreamAndConsentSet() throws Exception {
        String token=token();
        try(var pool=Executors.newFixedThreadPool(4)) {
            var futures=new ArrayList<Future<IdentityApi.Account>>();
            for(int i=0;i<8;i++) futures.add(pool.submit(()->identity.bootstrap("Bearer "+token,"Asia/Ho_Chi_Minh",consent())));
            Set<UUID> ids=new HashSet<>();
            for(var f:futures) ids.add(f.get(20,TimeUnit.SECONDS).id());
            assertThat(ids).hasSize(1); UUID id=ids.iterator().next();
            assertThat(jdbc.queryForObject("select count(*) from asl.user_consents where user_id=?",Integer.class,id)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from asl.sync_streams where user_id=?",Integer.class,id)).isEqualTo(1);
            var timestamps=jdbc.queryForList("select accepted_at from asl.user_consents where user_id=? order by document_id",id);
            identity.updateName("Bearer "+token,"Edited name");
            assertThat(identity.bootstrap("Bearer "+token,"UTC",List.of()).name()).isEqualTo("Edited name");
            assertThat(jdbc.queryForObject("select time_zone from asl.app_users where id=?",String.class,id)).isEqualTo("Asia/Ho_Chi_Minh");
            assertThat(jdbc.queryForList("select accepted_at from asl.user_consents where user_id=? order by document_id",id)).isEqualTo(timestamps);
        }
    }
    @Test void emailNeverMergesAccountsAndNullEmailWorks() {
        var first=identity.bootstrap("Bearer "+token(),"UTC",consent());
        var second=identity.bootstrap("Bearer "+token(),"UTC",consent());
        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(first.email()).isEqualTo(second.email());
        String apple="apple:"+UUID.randomUUID();
        var account=identity.bootstrap("Bearer "+apple,"UTC",consent());
        assertThat(account.email()).isNull();
        assertThat(identity.requireAccount("Bearer "+apple).id()).isEqualTo(account.id());
    }
    @Test void invalidConsentRollsBackNewAccountAndSuspendedCannotBootstrap() {
        String token=token(); String uid=token.substring(7);
        assertThatThrownBy(()->identity.bootstrap("Bearer "+token,"UTC",List.of(terms))).isInstanceOf(ApplicationFailure.class);
        assertThatThrownBy(()->identity.bootstrap("Bearer "+token,"UTC",List.of(UUID.randomUUID()))).isInstanceOf(ApplicationFailure.class);
        assertThat(jdbc.queryForObject("select count(*) from asl.app_users where firebase_uid=?",Integer.class,uid)).isZero();
        var account=identity.bootstrap("Bearer "+token,"UTC",consent());
        jdbc.update("update asl.app_users set suspended_at=now() where id=?",account.id());
        assertThatThrownBy(()->identity.bootstrap("Bearer "+token,"UTC",consent())).isInstanceOf(ApplicationFailure.class)
            .extracting(e->((ApplicationFailure)e).kind()).isEqualTo(ApplicationFailure.Kind.FORBIDDEN);
    }
    @Test void actualHttpContractLegalBootstrapMeAndPatch() throws Exception {
        capture("auth_legal",request("GET","/api/v1/legal-documents",null,null),200);
        String token=token();
        capture("account_profile-before-bootstrap",request("GET","/api/v1/me",token,null),401);
        String body=json.writeValueAsString(Map.of("timeZone","UTC","acceptedDocumentIds",consent()));
        var created=request("POST","/api/v1/auth/bootstrap",token,body);
        capture("auth_bootstrap",created,200);
        capture("account_profile",request("GET","/api/v1/me",token,null),200);
        capture("account_updateProfile",request("PATCH","/api/v1/me",token,"{\"name\":null}"),200);
        capture("auth_bootstrap-missing-consent",request("POST","/api/v1/auth/bootstrap",token(),"{\"timeZone\":\"UTC\",\"acceptedDocumentIds\":[]}"),422);
        capture("account_profile-revoked",request("GET","/api/v1/me","revoked:"+UUID.randomUUID(),null),401);
        capture("account_profile-provider",request("GET","/api/v1/me","password:"+UUID.randomUUID(),null),403);
        capture("account_profile-outage",request("GET","/api/v1/me","unavailable:"+UUID.randomUUID(),null),503);
        assertThat(request("GET","/api/v1/me",token,null).headers().firstValue("Cache-Control")).contains("private, no-store");
    }
    HttpResponse<String> request(String method,String path,String token,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));
        if(token!=null) builder.header("Authorization","Bearer "+token);
        if(body!=null) builder.header("Content-Type","application/json");
        return HttpClient.newHttpClient().send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    void capture(String name,HttpResponse<String> response,int status) throws Exception {
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(status);
        Path folder=Path.of("target/identity-http");Files.createDirectories(folder);
        Files.writeString(folder.resolve(name+"-"+status+".json"),response.body());
    }
}
