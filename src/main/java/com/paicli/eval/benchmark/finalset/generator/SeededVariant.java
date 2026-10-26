package com.paicli.eval.benchmark.finalset.generator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Domain-separated deterministic entropy for one private sibling variant. */
record SeededVariant(String caseId, String variantId, byte[] entropy) {
    static SeededVariant derive(byte[] seed, String caseId, String recipeVersion) {
        byte[] entropy = digest(("paicli-final-source/v1/case/" + caseId + "/recipe/"
                + recipeVersion + "\0").getBytes(StandardCharsets.UTF_8), seed);
        return new SeededVariant(caseId, HexFormat.of().formatHex(entropy, 0, 12), entropy);
    }

    int number(int offset, int minimum, int maximumInclusive) {
        if (minimum > maximumInclusive || offset < 0 || offset + 1 >= entropy.length) {
            throw new IllegalArgumentException("invalid deterministic number request");
        }
        int value = ((entropy[offset] & 0xff) << 8) | (entropy[offset + 1] & 0xff);
        return minimum + Math.floorMod(value, maximumInclusive - minimum + 1);
    }

    String word(int offset) {
        String[] words = {
                "Amber", "Beryl", "Cedar", "Delta", "Ember", "Flint", "Garnet", "Harbor",
                "Indigo", "Juniper", "Kestrel", "Lumen", "Mica", "Nimbus", "Onyx", "Prairie"
        };
        return words[Math.floorMod(entropy[offset] & 0xff, words.length)];
    }

    String shortToken(int offset) {
        return HexFormat.of().formatHex(entropy, offset, offset + 4);
    }

    private static byte[] digest(byte[] prefix, byte[] seed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(prefix);
            digest.update(seed);
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
