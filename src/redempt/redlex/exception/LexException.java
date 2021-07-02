package redempt.redlex.exception;

/**
 * Thrown when a Lexer cannot tokenize an input because the tokens are not satisfied
 */
public class LexException extends IllegalArgumentException {
	
	public LexException(String message) {
		super(message);
	}
	
}
