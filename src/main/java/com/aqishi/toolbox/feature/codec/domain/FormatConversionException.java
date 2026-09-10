package com.aqishi.toolbox.feature.codec.domain;

/**
 * Conversion or syntax error reported by the format conversion service.
 */
public class FormatConversionException extends IllegalArgumentException {

    private final int line;
    private final int column;

    public FormatConversionException(String message, int line, int column) {
        super(message);
        this.line = line;
        this.column = column;
    }

    public FormatConversionException(String message, int line, int column, Throwable cause) {
        super(message, cause);
        this.line = line;
        this.column = column;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }
}
