package com.aether.api;

import jakarta.validation.constraints.*;

/** Validated request DTOs. Boxed fields distinguish absent values from zero/false. */
public final class Requests {
    private Requests() {}
    public record Progress(@NotNull @DecimalMin("0") Double position,
                           @NotNull @DecimalMin(value="0", inclusive=false) @DecimalMax("86400") Double duration,
                           @NotNull @DecimalMin("0") @DecimalMax("1") Double volume,
                           @NotNull Boolean muted) {
        @AssertTrue(message="Playback position must be finite and inside the media timeline")
        public boolean isTimelineValid() {
            return position != null && duration != null && volume != null
                && Double.isFinite(position) && Double.isFinite(duration) && Double.isFinite(volume)
                && position <= duration;
        }
    }
    public record Reaction(@NotNull Boolean liked, @NotNull Boolean saved) {}
    public record Checkout(@NotBlank @Pattern(regexp="[a-z][a-z0-9_-]{1,39}") String plan,@NotNull java.util.UUID requestId) {}
    public record Draft(@NotBlank @Size(max=120) String name,
                        @NotBlank @Size(max=5000) String synopsis,
                        @NotNull @Pattern(regexp="Drama|Adventure|Documentary|Sci-Fi|Thriller") String genre,
                        @Pattern(regexp="films|series|episode") String kind,java.util.UUID seriesId,@Min(1) @Max(1000) Integer seasonNumber,@Min(1) @Max(10000) Integer episodeNumber,
                        @Pattern(regexp="signal-hero.jpg|neon-poster.jpg|wild-poster.jpg|orbit-poster.jpg|coast-poster.jpg") String poster) {
        public Draft(String name,String synopsis,String genre){this(name,synopsis,genre,"films",null,null,null,"signal-hero.jpg");}
    }
}
