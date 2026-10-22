package com.aether.api;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
/** Slow broker retries must not occupy the release clock's scheduler thread. */
@Configuration
public class ReleaseSchedulingConfiguration {
 @Bean(name="taskScheduler") ThreadPoolTaskScheduler jobs(){return scheduler("background-",2);}
 @Bean(name="releaseTaskScheduler") ThreadPoolTaskScheduler releases(){return scheduler("release-",1);}
 private ThreadPoolTaskScheduler scheduler(String prefix,int size){var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(size);scheduler.setThreadNamePrefix(prefix);scheduler.setRemoveOnCancelPolicy(true);return scheduler;}
}
