/*
 * Copyright (c) 2018-2020 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.kex.kasm;

/**
 * Represents a (possibly empty) subfield of text, parsed from a line of assembly code.
 */
public class TextSubfield {

    public final Locale _locale;        //  linenumber/column of this subfield within the source code set
    public final String _text;          //  text of the field, not including the prefixing asterisk (if it exists)

    /**
     * Constructor
     * @param locale location of this subfield of text within the source code set
     * @param text text of this subfield, not including the prefix asterisk (if it exists)
     */
    TextSubfield(
        final Locale locale,
        final String text
    ) {
        _locale = locale;
        _text = text;
    }

    /**
     * Retrieves a locale representing the lineNumber/column of the position
     * immediately following this subfield in the source code.
     * @return locale per above description
     */
    Locale getLocaleLimit(
    ) {
        return new Locale(_locale.getLineSpecifier(), _locale.getColumn() + _text.length());
    }
}
