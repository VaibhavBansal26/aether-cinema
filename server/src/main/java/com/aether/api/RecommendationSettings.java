package com.aether.api;

import jakarta.validation.constraints.*;

/** No provider keys/URLs are accepted from the browser. New policy is OFF unless explicitly enabled. */
public record RecommendationSettings(@NotNull Boolean enabled,
    @NotNull @Pattern(regexp="none|ollama") String provider,
    @NotNull @Size(max=120) @Pattern(regexp="[a-zA-Z0-9_./:-]*") String model,
    @NotNull @Min(0) Long revision) {
    @AssertTrue(message="Choose an Ollama model before enabling the LLM")
    public boolean isConfigurationValid() {
        return enabled==null || !enabled || ("ollama".equals(provider)&&model!=null&&!model.isBlank());
    }
}
