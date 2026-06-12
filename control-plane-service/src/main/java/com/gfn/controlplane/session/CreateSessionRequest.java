package com.gfn.controlplane.session;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateSessionRequest(
        @NotBlank String userId,
        @NotBlank String gameId,
        @NotNull Region region,
        @NotNull GpuProfile gpuProfile,
        @Min(10) @Max(250) int maxLatencyMs
) {
}

