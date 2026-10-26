package com.hycon.api;

import java.util.UUID;

/**
 * Adds reasons to a player's risk score on their profile (Hycon's Risk section). Register with
 * {@link HyconApi#registerRiskSource}. Since API version 6.
 *
 * <p>{@link #factors} runs on a Hycon web thread whenever the profile is opened, for online and
 * offline players alike: read thread-safe data only.
 */
public interface HyconRisk {

    /** Receives one reason: what it is, how many points it adds (0-100 scale), and a short detail. */
    interface Factors {
        void add(String what, int points, String detail);
    }

    void factors(UUID player, Factors out);
}
