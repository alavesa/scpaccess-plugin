package fi.alavesa.scpaccess.model;

/** A model that cannot be used at all (broken JSON, no cubes...). The message names the reason; the model is skipped. */
public final class ModelException extends Exception {

    private static final long serialVersionUID = 1L;

    public ModelException(String message) { super(message); }
}
