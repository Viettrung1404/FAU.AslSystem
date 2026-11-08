package com.asl.backend.sync.adapter.out.cursor;
import com.asl.backend.sync.application.port.out.SyncCursor;
import com.asl.backend.sync.application.api.SyncFailure;
import com.asl.backend.platform.application.api.ApplicationFailure.Kind;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HmacSyncCursor implements SyncCursor {
    private final byte[] key; private final Clock clock;
    public HmacSyncCursor(String secret,Clock clock) { this.clock=clock; key=secret==null||secret.isBlank()?null:secret.getBytes(StandardCharsets.UTF_8); if(key!=null&&key.length<32) throw new IllegalArgumentException("Sync cursor secret requires at least 32 bytes"); }
    public String encode(UUID owner,Position p) { byte[] bytes=("1|"+owner+"|"+p.mode()+"|"+p.watermark()+"|"+p.after()+"|"+p.expires()+"|"+p.baseline()).getBytes(StandardCharsets.UTF_8); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(sign(bytes)); }
    public Position decode(UUID owner,String token) {
        if(token==null||token.length()>1024||!token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) throw invalid();
        try {
            var parts=token.split("\\."); byte[] payload=Base64.getUrlDecoder().decode(parts[0]);
            if(!MessageDigest.isEqual(sign(payload),Base64.getUrlDecoder().decode(parts[1]))) throw invalid();
            var f=new String(payload,StandardCharsets.UTF_8).split("\\|",-1);
            if(f.length!=7||!f[0].equals("1")||!Set.of("sync","snapshot","changes").contains(f[2])) throw invalid();
            if(!owner.toString().equals(f[1])) throw new SyncFailure(Kind.NOT_FOUND,"NOT_FOUND","Sync cursor not found","/cursor",null);
            var p=new Position(f[2],Long.parseLong(f[3]),Long.parseLong(f[4]),Long.parseLong(f[5]),Long.parseLong(f[6]));
            if(p.watermark()<0||p.after()<0||p.after()>p.watermark()||p.baseline()<0||p.baseline()>p.watermark()) throw invalid();
            if(clock.instant().getEpochSecond()>=p.expires()) throw new SyncFailure(Kind.REMOVED,"SYNC_CURSOR_EXPIRED","Sync cursor expired; fetch a new snapshot","/cursor",null);
            return p;
        } catch(IllegalArgumentException e) { throw invalid(); }
    }
    private byte[] sign(byte[] value) {
        if(key==null) throw new SyncFailure(Kind.UNAVAILABLE,"SERVICE_UNAVAILABLE","Sync cursor configuration unavailable","/cursor",null);
        try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); return mac.doFinal(value); } catch(GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    private static SyncFailure invalid() { return new SyncFailure(Kind.BAD_REQUEST,"BAD_REQUEST","Invalid sync cursor","/cursor",null); }
}
