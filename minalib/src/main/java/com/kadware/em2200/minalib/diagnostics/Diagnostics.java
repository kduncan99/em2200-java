/*
 * Copyright (c) 2018-2019 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.em2200.minalib.diagnostics;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Wraps an in-order container of Diagnostic objects, providing some simple getters to quickly analyze the content thereof.
 */
public class Diagnostics {

    private final List<Diagnostic> _diagnostics = new LinkedList<>();
    private final Map<Diagnostic.Level, Integer> _counters = new HashMap<>();

    public Diagnostics(
    ) {
        _counters.put(Diagnostic.Level.Directive, 0);
        _counters.put(Diagnostic.Level.Duplicate, 0);
        _counters.put(Diagnostic.Level.Error, 0);
        _counters.put(Diagnostic.Level.Fatal, 0);
        _counters.put(Diagnostic.Level.Quote, 0);
        _counters.put(Diagnostic.Level.Relocation, 0);
        _counters.put(Diagnostic.Level.Truncation, 0);
        _counters.put(Diagnostic.Level.UndefinedReference, 0);
        _counters.put(Diagnostic.Level.Value, 0);
    }

    /**
     * Appends a Diagnostic object to our container, updating our counters as appropriate
     * @param diagnostic
     */
    public void append(
        final Diagnostic diagnostic
    ) {
        _diagnostics.add(diagnostic);
        Integer counter = _counters.get(diagnostic.getLevel());
        if (counter == null) {
            counter = 1;
        }
        _counters.put(diagnostic.getLevel(), counter);
    }

    /**
     * Appends all the Diagnostic objects from one container into this container, updating our counters as appropriate
     * @param diagnostics
     */
    public void append(
        final Diagnostics diagnostics
    ) {
        for (Diagnostic diag : diagnostics._diagnostics) {
            append(diag);
        }
    }

    /**
     * Clears all the Diagnostic objects from this container.
     * Should not really be used, I think.
     */
    public void clear(
    ) {
        _diagnostics.clear();
    }

    /**
     * Getter
     * @return array of all diagnostics
     */
    public Diagnostic[] getDiagnostics(
    ) {
        return _diagnostics.toArray(new Diagnostic[_diagnostics.size()]);
    }

    /**
     * Getter
     * @return array of all diagnostics pertaining to a given line number
     */
    public Diagnostic[] getDiagnostics(
        final int lineNumber
    ) {
        List<Diagnostic> diags = new LinkedList<>();
        for (Diagnostic d : _diagnostics) {
            if (d.getLocale().getLineNumber() == lineNumber) {
                diags.add(d);
            }
        }

        return diags.toArray(new Diagnostic[diags.size()]);
    }

    /**
     * Returns counters map
     * @return as above
     */
    public Map<Diagnostic.Level, Integer> getCounters(
    ) {
        return _counters;
    }

    /**
     * Returns true if we have at least one Fatal level diagnostic
     * @return as above
     */
    public boolean hasFatal(
    ) {
        Integer count = _counters.get(Diagnostic.Level.Fatal);
        return ((count != null) && (count > 0));
    }

    /**
     * Returns true if the container is empty
     * @return as above
     */
    public boolean isEmpty(
    ) {
        return _diagnostics.isEmpty();
    }
}
