/*
 * Copyright (c) 2018 by Kurt Duncan - All Rights Reserved
 */
package com.kadware.em2200.minalib.textParser;

import com.kadware.em2200.minalib.Locale;
import com.kadware.em2200.minalib.diagnostics.ErrorDiagnostic;
import com.kadware.em2200.minalib.diagnostics.QuoteDiagnostic;
import com.kadware.em2200.minalib.diagnostics.Diagnostics;
import java.util.ArrayList;

/**
 * Represents a line of source code
 */
public class TextLine {

    //  line number of this line of text
    private final int _lineNumber;

    //  source code for this line of text
    private final String _text;

    //  parsed TextField objects parsed from the line of text - may be empty
    private final ArrayList<TextField> _fields = new ArrayList<>();

    //  Diagnostic objects pertaining to this line of text
    private final Diagnostics _diagnostics = new Diagnostics();

    /**
     * Constructor
     * <p>
     * @param lineNumber
     * @param text
     */
    public TextLine(
        final int lineNumber,
        final String text
    ) {
        _lineNumber = lineNumber;
        _text = text;
    }

    /**
     * Getter
     * <p>
     * @return
     */
    public Diagnostics getDiagnostics(
    ) {
        return _diagnostics;
    }

    /**
     * Getter
     * <p>
     * @param index
     * <p>
     * @return
     */
    public TextField getField(
        final int index
    ) {
        if (index < _fields.size()) {
            return _fields.get(index);
        }
        return null;
    }

    /**
     * Returns number of fields, including inner void fields
     * <p>
     * @return
     */
    public int getFieldCount(
    ) {
        return _fields.size();
    }

    /**
     * Parses the text into TextField objects
     */
    public void parseFields(
    ) {
        //  We should only ever be called once, but just in case...
        _diagnostics.clear();
        _fields.clear();

        //  Create clean text string by removing all commentary and trailing whitespace.
        String cleanText = removeComments(_text);

        //  Get ready for iterating character-by-character over the line of code
        int tx = 0;

        //  If first character of the clean text is whitespace, skip all the whitespace,
        //  and generate a null entry in the TextField array list.
        if (!cleanText.isEmpty() && (cleanText.charAt(tx) == ' ')) {
            ++tx;
            while ((tx < cleanText.length()) && (cleanText.charAt(tx) == ' ')) {
                ++tx;
            }
            _fields.add(null);
        }

        //  Now we start parsing the clean text into fields, delimited by occurrences of one or more blank characters.
        //  The presumption is, at the top of the while loop, tx does NOT index a field-delimiting blank character.
        //  Note that we do NOT scan for syntax errors here - in particular, we are pretty lax with where parenthesis
        //  and especially quote delimiters are located.  As long as they are balanced properly, we are happy.
        int parenLevel = 0;
        boolean prevComma = false;
        boolean prevSign = false;
        boolean quoted = false;
        StringBuilder sb = new StringBuilder();
        Locale locale = new Locale(_lineNumber, tx + 1);
        while (tx < cleanText.length()) {
            char ch = cleanText.charAt(tx++);

            if (quoted) {
                sb.append(ch);
            } else {
                //  Look for a leading sign '+' or '-'...
                //  If found, skip any whitespace - it does NOT delimit this field
                //????TODO

                if ((parenLevel == 0) && (ch == ' ')) {
                    //  We have found an unquoted blank.  If the previous character was a comma,
                    //  this is whitespace embedded within a field, and should *not* terminate the field.
                    if (prevComma) {
                        sb.append(ch);
                        while ((tx < cleanText.length()) && (cleanText.charAt(tx) == ' ')) {
                            sb.append(' ');
                            ++tx;
                        }
                    } else if (prevSign) {
                        //  This is incidental whitespace following a unary prefix sign character.
                        //  Skip it, and any subsequent contiguous whitespace.
                        sb.append(ch);
                        while ((tx < cleanText.length()) && (cleanText.charAt(tx) == ' ')) {
                            sb.append(' ');
                            ++tx;
                        }
                    } else {
                        //  We've reached the end of the current field.
                        //  Create a TextField object, then parse the field text into subfields.
                        String fieldText = sb.toString();
                        TextField field = new TextField(locale, fieldText);
                        _fields.add(field);
                        Diagnostics parseDiags = field.parseSubfields();
                        _diagnostics.append(parseDiags);

                        //  Skip field-delimiting whitespace
                        while ((tx < cleanText.length()) && (cleanText.charAt(tx) == ' ')) {
                            ++tx;
                        }
                        sb = new StringBuilder();
                        locale = new Locale(_lineNumber, tx + 1);
                    }
                } else {
                    sb.append(ch);

                    //  Handle opening-closing parentheses
                    if (ch == '(') {
                        ++parenLevel;
                    } else if (ch == ')') {
                        if (parenLevel == 0) {
                            Locale diagLoc = new Locale(_lineNumber, tx);
                            _diagnostics.append(new ErrorDiagnostic(diagLoc, "Too many closing parentheses"));
                            return;
                        }
                        --parenLevel;
                    }
                }
            }

            if (ch == '\'') {
                quoted = !quoted;
            }

            prevComma = (ch == ',');
            prevSign = (sb.length() == 1) && ((ch == '+') || (ch == '-'));
        }

        String fieldText = sb.toString();
        if (fieldText.length() > 0) {
            TextField field = new TextField(locale, fieldText);
            _fields.add(field);
        }

        Locale endloc = new Locale(_lineNumber, tx - 1);
        if (quoted) {
            _diagnostics.append(new QuoteDiagnostic(endloc, "Unterminated string"));
        }

        if (parenLevel > 0) {
            _diagnostics.append(new ErrorDiagnostic(endloc, "Unterminated parenthesized expression"));
        }
    }

    /**
     * Remove comment from assembler text, if it exists.
     * Comments are signaled by space-period-space, or a period-space in the first two columns,
     * or a space-period in the last two columns, or by a single column containing a space.
     * <p>
     * @param text input text to be scanned
     * <p>
     * @return copy of input text with commentary removed, or the original text if no comment was found.
     */
    protected static String removeComments(
        final String text
    ) {
        if (text.equals(".") || text.startsWith(". ")) {
            return "";
        }

        boolean quoted = false;
        boolean prevSpace = true;
        int tx = 0;
        while (tx < text.length()) {
            char ch = text.charAt(tx++);
            if (!quoted) {
                if ((ch == '.') && prevSpace) {
                    if ((tx == text.length()) || (text.charAt(tx) == ' ')) {
                        return text.substring(0, tx - 2);
                    }
                }
            }

            prevSpace = (ch == ' ');
            if (ch == '\'') {
                quoted = !quoted;
            }
        }

        //  No comment found
        return text;
    }
}
