/*
 * Copyright (c) 2018 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.em2200.minalib.diagnostics;

import com.kadware.em2200.minalib.Locale;

/**
 * Class for diagnostics specific to directives
 */
public abstract class DirectiveDiagnostic extends Diagnostic {

    public DirectiveDiagnostic(
        final Locale locale,
        final String message
    ) {
        super(locale, message);
    }

    /**
     * Get the level associated with this instance
     * <p>
     * @return
     */
    @Override
    public Level getLevel(
    ) {
        return Level.Directive;
    }
}
