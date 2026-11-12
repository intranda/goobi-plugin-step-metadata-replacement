package de.intranda.goobi.plugins;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

public class YerushaMetadataReplacementPluginTest {

    @Test
    public void givenHashDelimiterWhenSplittingVocabularyValueThenReturnOneValuePerPart() {
        assertEquals(List.of("Aid and relief", "Aid and relief--Philanthropy and charity"),
                YerushaMetadataReplacementPlugin.splitVocabularyValue("Aid and relief#Aid and relief--Philanthropy and charity", "#"));
    }

    @Test
    public void givenNoDelimiterWhenSplittingVocabularyValueThenReturnValueUnchanged() {
        assertEquals(List.of("a#b"), YerushaMetadataReplacementPlugin.splitVocabularyValue("a#b", ""));
        assertEquals(List.of("a#b"), YerushaMetadataReplacementPlugin.splitVocabularyValue("a#b", null));
    }

    @Test
    public void givenValueWithoutDelimiterWhenSplittingVocabularyValueThenReturnSingleValue() {
        assertEquals(List.of("Aid and relief"), YerushaMetadataReplacementPlugin.splitVocabularyValue("Aid and relief", "#"));
    }

    @Test
    public void givenSpacesAndEmptyPartsWhenSplittingVocabularyValueThenTrimPartsAndSkipEmptyOnes() {
        assertEquals(List.of("a", "b"), YerushaMetadataReplacementPlugin.splitVocabularyValue(" a # #b# ", "#"));
    }

    @Test
    public void givenRegexAsDelimiterWhenSplittingVocabularyValueThenSplitAtEachMatch() {
        assertEquals(List.of("a", "b", "c"), YerushaMetadataReplacementPlugin.splitVocabularyValue("a;b#c", "[;#]"));
        assertEquals(List.of("a", "b"), YerushaMetadataReplacementPlugin.splitVocabularyValue("a|b", "\\|"));
    }

    @Test
    public void givenNoValueWhenSplittingVocabularyValueThenReturnNothing() {
        assertEquals(List.of(), YerushaMetadataReplacementPlugin.splitVocabularyValue(null, "#"));
        assertEquals(List.of(), YerushaMetadataReplacementPlugin.splitVocabularyValue(null, ""));
    }
}
