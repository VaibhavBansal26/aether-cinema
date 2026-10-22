package com.aether.api;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ReleaseTimingTest {
 @Test void immediateAndFutureIntentAreExplicit(){assertEquals("published",ReleaseTiming.plan(null,null).status());var at=Instant.now().plusSeconds(3600);var plan=ReleaseTiming.plan(at,"America/New_York");assertEquals("scheduled",plan.status());assertEquals(at,plan.at());assertEquals("America/New_York",plan.zone());}
 @Test void pastUnboundedAndInvalidZoneAreRejected(){assertThrows(ApiException.class,()->ReleaseTiming.plan(Instant.now().minusSeconds(1),"UTC"));assertThrows(ApiException.class,()->ReleaseTiming.plan(Instant.now().plus(Duration.ofDays(731)),"UTC"));assertThrows(ApiException.class,()->ReleaseTiming.plan(null,"Not/AZone"));}
}
