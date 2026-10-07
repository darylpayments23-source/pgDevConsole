package com.example.deploymentconsole.service;

import java.util.List;

/** Thrown when scripts changed since their last successful execution and the operator has not confirmed. */
public class ScriptsModifiedException extends RuntimeException {
    private final List<String> modifiedScripts;

    public ScriptsModifiedException(List<String> modifiedScripts) {
        super(modifiedScripts.size() + " script(s) have changed since their last successful execution. "
                + "Confirmation is required to deploy them.");
        this.modifiedScripts = List.copyOf(modifiedScripts);
    }

    public List<String> getModifiedScripts() { return modifiedScripts; }
}
