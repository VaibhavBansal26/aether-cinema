package com.aether.api;
import java.time.*;
import org.springframework.http.HttpStatus;
/** Normalize publication intent once; timestamps in the browser never authorize early playback. */
final class ReleaseTiming {
 record Plan(String status,Instant at,String zone){}
 static Plan plan(Instant releaseAt,String zone){
  String selected=zone==null||zone.isBlank()?"UTC":zone;
  try{if(selected.length()>80)throw new DateTimeException("zone");ZoneId.of(selected);}catch(DateTimeException e){throw new ApiException(HttpStatus.BAD_REQUEST,"Choose a valid release timezone");}
  Instant now=Instant.now();
  if(releaseAt!=null&&(!releaseAt.isAfter(now)||releaseAt.isAfter(now.plus(Duration.ofDays(730)))))throw new ApiException(HttpStatus.BAD_REQUEST,"Release must be in the future and within two years");
  return new Plan(releaseAt==null?"published":"scheduled",releaseAt==null?now:releaseAt,selected);
 }
}
