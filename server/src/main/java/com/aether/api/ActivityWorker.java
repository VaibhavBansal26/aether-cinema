package com.aether.api;

import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import io.micrometer.core.instrument.MeterRegistry;

/** PostgreSQL durable queue: locked claims, transactional effects and completion, bounded retries.
 * Multiple replicas safely compete with SKIP LOCKED. Process death rolls the claim back automatically.
 */
@Component
public class ActivityWorker {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final MeterRegistry metrics;
    private final boolean enabled;
    public ActivityWorker(JdbcTemplate db,PlatformTransactionManager manager,MeterRegistry metrics,
        @Value("${app.worker-enabled:false}") boolean enabled) {
        this.db=db;this.tx=new TransactionTemplate(manager);this.metrics=metrics;this.enabled=enabled;
        tx.setTimeout(5);
    }
    /** One job per transaction bounds lock duration and avoids external IO while holding a claim. */
    public boolean processOne() {
        return Boolean.TRUE.equals(tx.execute(status->{
            var jobs=db.queryForList("SELECT id,profile_id FROM activity_jobs WHERE state='pending' AND available_at<=now() ORDER BY available_at,id FOR UPDATE SKIP LOCKED LIMIT 1");
            if(jobs.isEmpty())return false;
            var job=jobs.getFirst();Object id=job.get("id");
            Object savepoint=status.createSavepoint();
            try {
                db.update("INSERT INTO profile_activity(profile_id,events,last_event_id) VALUES(?,1,?) ON CONFLICT(profile_id) DO UPDATE SET events=profile_activity.events+1,last_event_id=greatest(profile_activity.last_event_id,EXCLUDED.last_event_id),updated_at=now()",job.get("profile_id"),id);
                db.update("UPDATE activity_jobs SET state='done' WHERE id=?",id);
                status.releaseSavepoint(savepoint);
                metrics.counter("aether.jobs.completed").increment();
            } catch(Exception failure) {
                status.rollbackToSavepoint(savepoint);
                db.update("UPDATE activity_jobs SET attempts=attempts+1,state=CASE WHEN attempts>=4 THEN 'dead' ELSE 'pending' END,available_at=now()+make_interval(secs=>least(300,power(2,attempts+1)::int)) WHERE id=?",id);
                metrics.counter("aether.jobs.retried").increment();
            }
            return true;
        }));
    }
    /** Enable on worker deployments; API replicas may disable processing independently. */
    @Scheduled(fixedDelayString="${app.worker-delay-ms:1000}")
    public void drain() {
        if(!enabled)return;
        try { for(int i=0;i<50&&processOne();i++) {} }
        catch(Exception failure) {metrics.counter("aether.jobs.poll_failures").increment();}
    }
}
