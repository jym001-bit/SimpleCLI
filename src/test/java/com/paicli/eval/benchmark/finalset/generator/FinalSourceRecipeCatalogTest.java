package com.paicli.eval.benchmark.finalset.generator;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalSourceRecipeCatalogTest {
    @Test
    void registersTheExactTwentyEightBlueprintCasesAndWeights() {
        List<FinalSourceRecipeCatalog.Recipe> recipes = FinalSourceRecipeCatalog.recipes();

        assertEquals(28, recipes.size());
        assertEquals(List.of(
                "A1", "A2", "A3", "A4",
                "B1", "B2", "B3", "B4", "B5", "B6",
                "C1", "C2", "C3",
                "D1", "D2", "D3", "D4", "D5",
                "E1", "E2", "E3", "E4",
                "F1", "F2", "F3", "F4",
                "G1", "G2"), recipes.stream().map(FinalSourceRecipeCatalog.Recipe::id).toList());
        assertEquals(100, recipes.stream().mapToInt(FinalSourceRecipeCatalog.Recipe::weight).sum());

        EnumMap<FinalSourceRecipeCatalog.Level, Integer> levelWeights =
                new EnumMap<>(FinalSourceRecipeCatalog.Level.class);
        recipes.forEach(recipe -> levelWeights.merge(
                recipe.level(), recipe.weight(), Integer::sum));
        assertEquals(40, levelWeights.get(FinalSourceRecipeCatalog.Level.L1));
        assertEquals(36, levelWeights.get(FinalSourceRecipeCatalog.Level.L2));
        assertEquals(24, levelWeights.get(FinalSourceRecipeCatalog.Level.L3));
        assertEquals(1, recipes.stream()
                .filter(recipe -> recipe.mode() == FinalSourceRecipeCatalog.Mode.PLAN).count());
        assertEquals(1, recipes.stream()
                .filter(recipe -> recipe.mode() == FinalSourceRecipeCatalog.Mode.TEAM).count());
    }

    @Test
    void onlyTheReferencePrototypeRecipesAreImplementedAndEveryOtherRecipeFailsClosed() {
        assertEquals(List.of(
                        "A1", "A2", "A3", "A4", "B1", "B2", "B3", "B4", "B5", "B6",
                        "C1", "C2", "C3", "D1", "D2", "D3", "D4", "E1", "E2", "F1", "F2", "F3", "F4", "G1", "G2"),
                FinalSourceRecipeCatalog.implementedIds());
        assertEquals(3, FinalSourceRecipeCatalog.missingIds().size());
        assertEquals("D5", FinalSourceRecipeCatalog.missingIds().get(0));
        assertEquals("E4", FinalSourceRecipeCatalog.missingIds().get(2));
        assertEquals("MOCK_MCP_FILE_ONLY", FinalSourceRecipeCatalog.require("F3").toolProfile());
        assertEquals("FILE_ONLY", FinalSourceRecipeCatalog.require("E1").toolProfile());
        assertTrue(FinalSourceRecipeCatalog.recipes().stream()
                .filter(recipe -> recipe.status()
                        == FinalSourceRecipeCatalog.ImplementationStatus.PLANNED)
                .allMatch(recipe -> !recipe.failClosedReason().isBlank()));
        assertFalse(FinalSourceRecipeCatalog.require("A2").publicPrompt().isBlank());
        assertEquals("CODE_RAG", FinalSourceRecipeCatalog.require("A2").toolProfile());
        assertEquals("REASONING_ONLY", FinalSourceRecipeCatalog.require("G1").toolProfile());
        assertEquals("REASONING_ONLY", FinalSourceRecipeCatalog.require("G2").toolProfile());
        assertEquals("LOCAL_COMMAND", FinalSourceRecipeCatalog.require("C1").toolProfile());
        assertEquals("LOCAL_COMMAND", FinalSourceRecipeCatalog.require("C2").toolProfile());
        assertEquals("LOCAL_COMMAND", FinalSourceRecipeCatalog.require("C3").toolProfile());
        assertEquals(FinalSourceRecipeCatalog.ImplementationStatus.IMPLEMENTED,
                FinalSourceRecipeCatalog.require("C1").status());
        assertEquals(FinalSourceRecipeCatalog.ImplementationStatus.IMPLEMENTED,
                FinalSourceRecipeCatalog.require("C2").status());
        assertEquals(FinalSourceRecipeCatalog.ImplementationStatus.IMPLEMENTED,
                FinalSourceRecipeCatalog.require("C3").status());
        assertEquals(FinalSourceRecipeCatalog.ImplementationStatus.IMPLEMENTED,
                FinalSourceRecipeCatalog.require("G1").status());
        assertEquals(FinalSourceRecipeCatalog.ImplementationStatus.IMPLEMENTED,
                FinalSourceRecipeCatalog.require("G2").status());
        assertThrows(IllegalStateException.class, FinalSourceRecipeCatalog::requireFinalReady);
    }
}
