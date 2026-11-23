package de.atstck.kitly.cal.config;

import java.util.List;

/** The configuration is unusable. Lists every problem at once so one restart is enough to fix them. */
public class ConfigException extends RuntimeException {

    private final List<String> problems;

    public ConfigException(List<String> problems) {
        super("Invalid kitly-cal configuration:\n- " + String.join("\n- ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
