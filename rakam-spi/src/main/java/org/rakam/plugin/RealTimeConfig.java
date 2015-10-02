package org.rakam.plugin;

import io.airlift.configuration.Config;
import io.airlift.units.Duration;
import io.airlift.units.MinDuration;


public class RealTimeConfig {
    private Duration timeout = Duration.valueOf("45s");
    private Duration updateInterval = Duration.valueOf("5s");
    private boolean enabled;

    @Config("real-time.enabled")
    public void setRealtimeModuleEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRealtimeModuleEnabled() {
        return enabled;
    }

    @MinDuration("1s")
    public Duration getTimeout()
    {
        return timeout;
    }

    @Config("realtime.timeout")
    public RealTimeConfig setTimeout(String timeout)
    {
        if(timeout != null)
            this.timeout = Duration.valueOf(timeout);
        return this;
    }
}
