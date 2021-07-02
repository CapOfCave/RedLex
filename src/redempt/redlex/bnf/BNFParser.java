package redempt.redlex.bnf;

import redempt.redlex.data.Token;
import redempt.redlex.data.TokenType;
import redempt.redlex.processing.CullStrategy;
import redempt.redlex.processing.Lexer;
import redempt.redlex.processing.TokenFilter;
import redempt.redlex.processing.TraversalOrder;
import redempt.redlex.token.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A parser used to create lexers from BNF files
 */
public class BNFParser {
	
	private static BNFParser parser;
	
	/**
	 * @return The BNFParser instance
	 */
	public static BNFParser getParser() {
		if (parser == null) {
			Lexer lexer = BNFLexer.getLexer();
			parser = new BNFParser(lexer);
		}
		return parser;
	}
	
	private Lexer lexer;
	
	private BNFParser(Lexer lexer) {
		this.lexer = lexer;
	}
	
	/**
	 * Parses the input String and returns a Lexer
	 * @param input The input String defining the format for the Lexer
	 * @return A Lexer for the given format
	 */
	public Lexer createLexer(String input) {
		return new Lexer(parse(input));
	}
	
	/**
	 * Parses the input String and returns a Lexer
	 * @param path The path to a file containing the format for the Lexer
	 * @return A Lexer for the given format
	 */
	public Lexer createLexer(Path path) {
		try {
			String contents = Files.lines(path).collect(Collectors.joining("\n"));
			return createLexer(contents);
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}
	
	private TokenType parse(String input) {
		Token token = lexer.tokenize(input);
		token.cull(TokenFilter.removeEmpty(),
				TokenFilter.removeUnnamed(CullStrategy.DELETE_ALL),
				TokenFilter.byName(CullStrategy.DELETE_ALL, "whitespace", "::=", "comment"),
				TokenFilter.byName(CullStrategy.LIFT_CHILDREN, "modifiers", "statementList"));
		Map<String, List<Token>> map = token.allByNames(TraversalOrder.DEPTH_LEAF_FIRST,
				"escapeSequence", "statementOpt", "token", "sentence", "nested");
		for (Token escape : map.get("escapeSequence")) {
			Token anyChar = escape.firstByName("anyChar");
			escape.firstByName("escape").setValue("");
			switch (anyChar.getValue().charAt(0)) {
				case 'n':
					anyChar.setValue("\n");
					break;
				case 't':
					anyChar.setValue("\t");
					break;
				default:
					break;
			}
		}
		for (Token statementOpt : map.get("statementOpt")) {
			statementOpt.getChildren()[0].liftChildren();
			statementOpt.liftChildren();
		}
		Map<String, TokenType> tokens = new HashMap<>();
		for (Token t : map.get("token")) {
			TokenType type = createToken(t, tokens);
			t.replaceWith(type);
		}
		for (Token t : map.get("sentence")) {
			String name = t.firstByName("word").getValue();
			TokenType type = processSentence(t);
			if (!(type instanceof PlaceholderToken)) {
				type.setName(name);
			}
			tokens.put(name, type);
		}
		TokenType root = tokens.get("root");
		if (root == null) {
			throw new IllegalArgumentException("No root node specified");
		}
		Set<String> used = new HashSet<>();
		while (root instanceof PlaceholderToken) {
			if (!used.add(root.getName())) {
				throw new IllegalArgumentException("Circular reference or undefined tokens: " + String.join(", ", used));
			}
			root = tokens.get(root.getName());
		}
		root.replacePlaceholders(tokens);
		return root;
	}
	
	private TokenType createToken(Token input, Map<String, TokenType> map) {
		Token[] children = input.getChildren();
		boolean not = children[0].getType().getName().equals("!");
		Token token = children[not ? 1 : 0];
		TokenType type = null;
		switch (token.getType().getName()) {
			case "string":
				type = createString(token.firstByName("strOpt"));
				break;
			case "charset":
				type = createCharset(token);
				break;
			case "chargroup":
				type = createCharGroup(token);
				break;
			case "word":
				type = createTokenReference(token);
				map.putIfAbsent(type.getName(), type);
				break;
			case "eof":
				type = new EndOfFileToken(null);
				break;
		}
		Token modifier = input.firstByName("modifier");
		type = processModifier(type, modifier);
		if (not) {
			type = new NotToken(null, type);
		}
		return type;
	}
	
	private TokenType processModifier(TokenType type, Token modifier) {
		if (modifier != null) {
			switch (modifier.getValue().charAt(0)) {
				case '+':
					type = new RepeatingToken(null, type);
					break;
				case '*':
					type = new OptionalToken(null, new RepeatingToken(null, type));
					break;
				case '?':
					type = new OptionalToken(null, type);
					break;
			}
		}
		return type;
	}
	
	private TokenType processSentence(Token sentence) {
		for (Token t : sentence.allByName(TraversalOrder.DEPTH_LEAF_FIRST, "nested")) {
			Token statement = t.firstByName("statement");
			TokenType token = createStatement(statement);
			Token mod = t.firstByName("modifier");
			if (mod != null) {
				token = processModifier(token, mod);
			}
			if (t.firstByName("!") != null) {
				token = new NotToken(null, token);
			}
			t.replaceWith(token);
		}
		return createStatement(sentence.firstByName("statement"));
	}
	
	private TokenType createStatement(Token statement) {
		statement.cull(TokenFilter.byName(CullStrategy.LIFT_CHILDREN, "statement"));
		List<List<Token>> split = statement.splitChildren("|");
		List<TokenType> merged = new ArrayList<>();
		for (List<Token> list : split) {
			if (list.size() == 1) {
				merged.add((TokenType) list.get(0).getObject());
				continue;
			}
			TokenType[] arr = new TokenType[list.size()];
			for (int i = 0; i < list.size(); i++) {
				arr[i] = (TokenType) list.get(i).getObject();
			}
			merged.add(new ListToken(null, arr));
		}
		if (merged.size() == 1) {
			return merged.get(0);
		}
		return new ChoiceToken(null, merged.toArray(new TokenType[0]));
	}
	
	private TokenType createString(Token strOpt) {
		if (strOpt == null) {
			return new StringToken(null, "");
		}
		String val = strOpt.joinLeaves("");
		return new StringToken("'" + val, strOpt.joinLeaves(""));
	}
	
	private TokenType createTokenReference(Token t) {
		return new PlaceholderToken(t.getValue());
	}
	
	private TokenType createCharset(Token token) {
		Token caret = token.firstByName("^");
		if (caret != null) {
			caret.remove();
		}
		Token setOpt = token.firstByName("setOpt");
		if (setOpt == null) {
			return new CharSetToken(null, caret != null);
		}
		return new CharSetToken(null, caret != null, setOpt.joinLeaves("").toCharArray());
	}
	
	private TokenType createCharGroup(Token charGroup) {
		Token caret = charGroup.firstByName("^");
		if (caret != null) {
			caret.remove();
		}
		String set = charGroup.joinLeaves("");
		return new CharGroupToken(null, set.charAt(1), set.charAt(3), caret != null);
	}
	
}
