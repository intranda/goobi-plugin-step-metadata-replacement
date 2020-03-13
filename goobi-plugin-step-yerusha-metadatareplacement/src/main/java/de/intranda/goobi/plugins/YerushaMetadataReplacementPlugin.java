package de.intranda.goobi.plugins;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;
import org.goobi.vocabulary.Field;
import org.goobi.vocabulary.VocabRecord;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.VocabularyManager;
import lombok.Data;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.WriteException;

@PluginImplementation
@Log4j2
public class YerushaMetadataReplacementPlugin implements IStepPluginVersion2 {

    @Getter
    private PluginGuiType pluginGuiType = PluginGuiType.NONE;
    @Getter
    private PluginType type = PluginType.Step;

    @Getter
    private Step step;
    private Process process;

    @Getter
    private String title = "intranda_step_metadata_replacement";

    @Getter
    private int interfaceVersion = 1;

    private ReplacementConfiguration configuration;

    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;
        process = step.getProzess();
        String projectName = process.getProjekt().getTitel();

        XMLConfiguration xmlConfig = ConfigPlugins.getPluginConfig(getTitle());
        xmlConfig.setExpressionEngine(new XPathExpressionEngine());
        xmlConfig.setReloadingStrategy(new FileChangedReloadingStrategy());

        SubnodeConfiguration myconfig = null;

        // order of configuration is:
        // 1.) project name and step name matches
        // 2.) step name matches and project is *
        // 3.) project name matches and step name is *
        // 4.) project name and step name are *
        try {
            myconfig = xmlConfig.configurationAt("//config[./project = '" + projectName + "'][./step = '" + step.getTitel() + "']");
        } catch (IllegalArgumentException e) {
            try {
                myconfig = xmlConfig.configurationAt("//config[./project = '*'][./step = '" + step.getTitel() + "']");
            } catch (IllegalArgumentException e1) {
                try {
                    myconfig = xmlConfig.configurationAt("//config[./project = '" + projectName + "'][./step = '*']");
                } catch (IllegalArgumentException e2) {
                    myconfig = xmlConfig.configurationAt("//config[./project = '*'][./step = '*']");
                }
            }
        }
        configuration = new ReplacementConfiguration(myconfig);

    }

    @Override
    public String cancel() {
        return "";
    }

    @Override
    public String finish() {
        return "";
    }

    @Override
    public boolean execute() {
        PluginReturnValue ret = run();
        if (ret.equals(PluginReturnValue.FINISH)) {
            return true;
        }
        return false;
    }

    @Override
    public PluginReturnValue run() {

        try {
            // read mets file
            Fileformat ff = step.getProzess().readMetadataFile();
            Prefs prefs = step.getProzess().getRegelsatz().getPreferences();

            DocStruct logical = ff.getDigitalDocument().getLogicalDocStruct();
            DocStruct anchor = null;
            if (logical.getType().isAnchor()) {
                anchor = logical;
                logical = logical.getAllChildren().get(0);
            }
            for (ReplacementEntry entry : configuration.getEntryList()) {

                if (anchor != null) {
                    checkMetadata(prefs, anchor, entry);
                }
                checkMetadata(prefs, logical, entry);
            }
            step.getProzess().writeMetadataFile(ff);
        } catch (ReadException | PreferencesException | WriteException | IOException | InterruptedException | SwapException | DAOException e) {
            log.error(e);
        }

        return PluginReturnValue.FINISH;
    }

    private void checkMetadata(Prefs prefs, DocStruct docstruct, ReplacementEntry entry) {
        // find original metadata and generated metadata from previous runs
        List<Metadata> originalMetadata = new ArrayList<>();
        List<Metadata> generatedMetadataList = new ArrayList<>();
        for (Metadata md : docstruct.getAllMetadata()) {
            if (md.getType().getName().equals(entry.getOriginalMetadataName())) {
                originalMetadata.add(md);
            } else if (md.getType().getName().equals(entry.getGeneratedMetadataName())) {
                generatedMetadataList.add(md);
            }
        }
        // remove old generated metadata
        if (!generatedMetadataList.isEmpty()) {
            for (Metadata md : generatedMetadataList) {
                docstruct.removeMetadata(md);
            }
        }

        // read original metadata
        if (!originalMetadata.isEmpty()) {
            for (Metadata md : originalMetadata) {
                String value = md.getValue();
                // split it on semikolon to separate values
                String[] splittedValues = value.split(";");

                // for each value generate new metadata
                for (String splittedValue : splittedValues) {
                    try {
                        Metadata newMetadata = new Metadata(prefs.getMetadataTypeByName(entry.getGeneratedMetadataName()));
                        // get normed value from configured vocabulary
                        newMetadata.setValue(getNormedValue(splittedValue.trim(), entry));
                        docstruct.addMetadata(newMetadata);

                    } catch (MetadataTypeNotAllowedException e) {
                        log.error(e);
                    }
                }
            }
        }
    }

    private String getNormedValue(String value, ReplacementEntry entry) {

        // search for a record containing the search value
        List<VocabRecord> records =  VocabularyManager.findRecords(entry.getVocabularyName(), value, entry.getAlternativeValueColumnName());
        if (records != null && !records.isEmpty()) {
            List<Field> fields =records.get(0).getFields();
            for (Field field : fields) {
                // if record was found, get the normed value
                if (field.getLabel().equals(entry.getNormedValueColumnName())) {
                    return field.getValue();
                }
            }
        }
        // return the original value, if no record was found
        return value;
    }

    @Override
    public String getPagePath() {
        return null;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Data
    private class ReplacementConfiguration {

        private List<ReplacementEntry> entryList = new ArrayList<>();

        public ReplacementConfiguration(SubnodeConfiguration sub) {
            List<HierarchicalConfiguration> entries = sub.configurationsAt("entry");
            for (HierarchicalConfiguration hc : entries) {
                ReplacementEntry entry = new ReplacementEntry(hc);
                entryList.add(entry);
            }
        }
    }

    @Data
    private class ReplacementEntry {
        private String originalMetadataName;
        private String generatedMetadataName;
        private String vocabularyName;
        private String normedValueColumnName;
        private String alternativeValueColumnName;

        public ReplacementEntry(HierarchicalConfiguration sub) {
            originalMetadataName = sub.getString("checkMetadata");
            generatedMetadataName = sub.getString("createMetadata");
            vocabularyName = sub.getString("vocabulary");
            alternativeValueColumnName = sub.getString("fieldToCheck");
            normedValueColumnName = sub.getString("controlledField");
        }
    }

}
