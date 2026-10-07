package org.opentripplanner.ext.opensidewalks;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * A canonical text form of JSON, used to derive stable content identifiers (md5) for pedestrian
 * cost profiles and run specifications. Two JSON documents that differ only in whitespace, object
 * key order, or number formatting (1.0 vs 1, 0.10 vs 0.1, 1e-2 vs 0.01) have the same canonical
 * form and therefore the same identifier.
 * <p>
 * The rules are the same as in the R5 and Unweaver implementations of pedestrian cost profiles,
 * so all three report the same identifier for the same profile:
 * <ul>
 *   <li>Objects: keys sorted by Unicode code point, written as {"k":v,...} with no whitespace.</li>
 *   <li>Arrays: order preserved, [a,b,...].</li>
 *   <li>Numbers: exact decimal value with trailing zeros removed, in plain (non-exponent)
 *   notation. -0 becomes 0.</li>
 *   <li>Strings: JSON-escaped with only the mandatory escapes (quote, backslash, control
 *   characters); non-ASCII as-is.</li>
 *   <li>true, false, null as literals.</li>
 * </ul>
 */
final class CanonicalJson {

  private CanonicalJson() {}

  static String canonicalize(JsonNode node) {
    var sb = new StringBuilder();
    write(node, sb);
    return sb.toString();
  }

  static String md5OfCanonical(JsonNode node) {
    try {
      byte[] digest = MessageDigest.getInstance("MD5").digest(
        canonicalize(node).getBytes(StandardCharsets.UTF_8)
      );
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void write(JsonNode node, StringBuilder sb) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      sb.append("null");
    } else if (node.isObject()) {
      List<String> keys = new ArrayList<>();
      node.fieldNames().forEachRemaining(keys::add);
      // String.compareTo orders by UTF-16 code unit, which differs from code point order for
      // surrogate pairs. Compare by code points to match the other implementations.
      keys.sort(CanonicalJson::compareCodePoints);
      sb.append('{');
      boolean first = true;
      for (String key : keys) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        writeString(key, sb);
        sb.append(':');
        write(node.get(key), sb);
      }
      sb.append('}');
    } else if (node.isArray()) {
      sb.append('[');
      for (int i = 0; i < node.size(); i++) {
        if (i > 0) {
          sb.append(',');
        }
        write(node.get(i), sb);
      }
      sb.append(']');
    } else if (node.isNumber()) {
      sb.append(canonicalNumber(node.decimalValue()));
    } else if (node.isBoolean()) {
      sb.append(node.booleanValue());
    } else {
      writeString(node.asText(), sb);
    }
  }

  private static String canonicalNumber(BigDecimal value) {
    if (value.signum() == 0) {
      return "0";
    }
    return value.stripTrailingZeros().toPlainString();
  }

  private static void writeString(String s, StringBuilder sb) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }

  private static int compareCodePoints(String a, String b) {
    int i = 0;
    int j = 0;
    while (i < a.length() && j < b.length()) {
      int ca = a.codePointAt(i);
      int cb = b.codePointAt(j);
      if (ca != cb) {
        return Integer.compare(ca, cb);
      }
      i += Character.charCount(ca);
      j += Character.charCount(cb);
    }
    return Integer.compare(a.length() - i, b.length() - j);
  }
}
