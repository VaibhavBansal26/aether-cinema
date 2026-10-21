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
    public record Checkout(@NotBlank @Pattern(regexp="essential|cinema|collective") String plan) {}
    public record Draft(@NotBlank @Size(max=120) String name,
                        @NotBlank @Size(max=5000) String synopsis,
                        @NotNull @Pattern(regexp="Drama|Adventure|Documentary|Sci-Fi|Thriller") String genre) {}
}
