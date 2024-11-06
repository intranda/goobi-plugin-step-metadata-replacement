package de.intranda.goobi.plugins;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.intranda.digiverso.normdataimporter.NormDataImporter;
import de.intranda.digiverso.normdataimporter.model.MarcRecord;
import de.intranda.digiverso.normdataimporter.model.MarcRecord.DatabaseUrl;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.exceptions.SwapException;
import io.goobi.vocabulary.exchange.FieldDefinition;
import io.goobi.vocabulary.exchange.TranslationInstance;
import io.goobi.vocabulary.exchange.VocabularySchema;
import io.goobi.workflow.api.vocabulary.VocabularyAPIManager;
import io.goobi.workflow.api.vocabulary.helper.ExtendedFieldInstance;
import io.goobi.workflow.api.vocabulary.helper.ExtendedVocabulary;
import io.goobi.workflow.api.vocabulary.helper.ExtendedVocabularyRecord;
import lombok.Data;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataGroup;
import ugh.dl.MetadataGroupType;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.WriteException;

@PluginImplementation
@Log4j2
public class YerushaMetadataReplacementPlugin implements IStepPluginVersion2 {

    private static final long serialVersionUID = -5817542298898922714L;
    @Getter
    private PluginGuiType pluginGuiType = PluginGuiType.NONE;
    @Getter
    private PluginType type = PluginType.Step;

    @Getter
    private Step step;

    @Getter
    private String title = "intranda_step_metadata_replacement";

    @Getter
    private int interfaceVersion = 1;

    private ReplacementConfiguration configuration;

    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;
        SubnodeConfiguration myconfig = ConfigPlugins.getProjectAndStepConfig(title, step);
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
        return PluginReturnValue.FINISH.equals(run());
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
        } catch (ReadException | PreferencesException | WriteException | IOException | SwapException e) {
            log.error(e);
        }

        return PluginReturnValue.FINISH;
    }

    private void checkMetadata(Prefs prefs, DocStruct docstruct, ReplacementEntry entry) {

        if (StringUtils.isNotBlank(entry.getMetadataGroup())) {
            changeMetadataGroups(prefs, docstruct, entry);
        } else {
            changeMetadata(prefs, docstruct, entry);
        }
    }

    private void changeMetadataGroups(Prefs prefs, DocStruct ds, ReplacementEntry entry) {

        MetadataGroupType mgt = prefs.getMetadataGroupTypeByName(entry.getMetadataGroup());

        // run through all configured groups
        for (MetadataGroup group : ds.getAllMetadataGroupsByType(mgt)) {

            // collect original metadata and generated fields
            List<Metadata> originalMetadata = new ArrayList<>();
            List<Metadata> generatedMetadataList = new ArrayList<>();
            for (Metadata md : group.getMetadataList()) {
                if (md.getType().getName().equals(entry.getFieldFrom())) {
                    originalMetadata.add(md);
                } else if (md.getType().getName().equals(entry.getFieldTo())) {
                    generatedMetadataList.add(md);
                }
            }

            // remove old generated metadata
            if (entry.deleteExistingFieldTo && !generatedMetadataList.isEmpty()) {
                for (Metadata md : generatedMetadataList) {
                    group.removeMetadata(md, true);
                }
            }
            for (Metadata md : originalMetadata) {
                String value = md.getValue();

                // split the original metadata at delimiter to separate values
                String[] splitValues = new String[] { value };
                if (entry.metadataDelimiter != null && entry.metadataDelimiter.length() > 0) {
                    splitValues = value.split(entry.metadataDelimiter);
                }
                // for each value generate new metadata
                for (String splittedValue : splitValues) {
                    try {
                        // get normed value from configured vocabulary

                        List<Metadata> newListMd = getNormedMetadata(splittedValue.trim(), entry, prefs, md);
                        for (Metadata newMetadata : newListMd) {

                            // first run through all existing metadata to make sure it is not there already - to not have it twice
                            boolean newFieldExistsAlready = false;
                            for (Metadata mdTemp : group.getMetadataList()) {
                                if (mdTemp.getType().getName().equals(newMetadata.getType().getName())
                                        && mdTemp.getValue().equals(newMetadata.getValue())) {
                                    newFieldExistsAlready = true;
                                    break;
                                }
                            }
                            if (!newFieldExistsAlready) {
                                group.addMetadata(newMetadata);
                            }
                        }
                    } catch (MetadataTypeNotAllowedException e) {
                        log.error(e);
                    }
                }
            }

            // remove duplicated fieldTo metadata if wanted
            if (entry.removeDuplicatedFieldTo) {
                List<Metadata> temp = new ArrayList<>(group.getMetadataByType(entry.getFieldTo()));
                List<String> knownList = new ArrayList<>();
                for (Metadata mdTemp : temp) {
                    String v = mdTemp.getValue();
                    if (knownList.contains(v)) {
                        group.removeMetadata(mdTemp, true);
                    } else {
                        knownList.add(v);
                    }
                }
            }
        }
    }

    private void changeMetadata(Prefs prefs, DocStruct docstruct, ReplacementEntry entry) {
        // find original metadata and generated metadata from previous runs

        List<Metadata> originalMetadata = new ArrayList<>();
        List<Metadata> generatedMetadataList = new ArrayList<>();
        for (Metadata md : docstruct.getAllMetadata()) {
            if (md.getType().getName().equals(entry.getFieldFrom())) {
                originalMetadata.add(md);
            } else if (md.getType().getName().equals(entry.getFieldTo())) {
                generatedMetadataList.add(md);
            }
        }
        // remove old generated metadata
        if (entry.deleteExistingFieldTo && !generatedMetadataList.isEmpty()) {
            for (Metadata md : generatedMetadataList) {
                docstruct.removeMetadata(md);
            }
        }

        // read original metadata
        if (!originalMetadata.isEmpty()) {
            for (Metadata md : originalMetadata) {
                String value = md.getValue();

                // split the original metadata at delimiter to separate values
                String[] splitValues = new String[] { value };
                if (entry.metadataDelimiter != null && entry.metadataDelimiter.length() > 0) {
                    splitValues = value.split(entry.metadataDelimiter);
                }

                // for each value generate new metadata
                for (String splittedValue : splitValues) {
                    try {
                        // get normed value from configured vocabulary

                        List<Metadata> newListMd = getNormedMetadata(splittedValue.trim(), entry, prefs, md);
                        for (Metadata newMetadata : newListMd) {

                            // first run through all existing metadata to make sure it is not there already - to not have it twice
                            boolean newFieldExistsAlready = false;
                            for (Metadata mdTemp : docstruct.getAllMetadata()) {
                                if (mdTemp.getType().getName().equals(newMetadata.getType().getName())
                                        && mdTemp.getValue().equals(newMetadata.getValue())) {
                                    newFieldExistsAlready = true;
                                    break;
                                }

                            }

                            if (!newFieldExistsAlready) {
                                docstruct.addMetadata(newMetadata);
                            }
                        }
                    } catch (MetadataTypeNotAllowedException e) {
                        log.error(e);
                    }
                }
            }
        }

        // remove duplicated fieldTo metadata if wanted
        if (entry.removeDuplicatedFieldTo) {
            List<Metadata> temp = new ArrayList<>(docstruct.getAllMetadataByType(prefs.getMetadataTypeByName(entry.getFieldTo())));
            List<String> knownList = new ArrayList<>();
            for (Metadata mdTemp : temp) {
                String v = mdTemp.getValue();
                if (knownList.contains(v)) {
                    docstruct.removeMetadata(mdTemp);
                } else {
                    knownList.add(v);
                }
            }
        }
    }

    private List<Metadata> getNormedMetadata(String value, ReplacementEntry entry, Prefs prefs, Metadata originalMetadata)
            throws MetadataTypeNotAllowedException {
        List<Metadata> listMd = new ArrayList<>();

        performVocabularyBasedMetadataUpdates(listMd, value, entry, prefs, originalMetadata);

        // return the original value, if no record was found and if it should be duplicated
        if (listMd.isEmpty() && entry.duplicateIfMissing) {
            Metadata md = new Metadata(prefs.getMetadataTypeByName(entry.getFieldTo()));
            md.setValue(value);
            md.setAuthorityID(originalMetadata.getAuthorityID());
            md.setAuthorityURI(originalMetadata.getAuthorityURI());
            md.setAuthorityValue(originalMetadata.getAuthorityValue());
            listMd.add(md);
        }
        return listMd;
    }

    private void performVocabularyBasedMetadataUpdates(List<Metadata> resultList, String value, ReplacementEntry entry, Prefs prefs,
            Metadata originalMetadata) throws MetadataTypeNotAllowedException {
        // search for a record containing the search value
        ExtendedVocabulary vocabulary = VocabularyAPIManager.getInstance().vocabularies().findByName(entry.getVocabulary());
        VocabularySchema schema = VocabularyAPIManager.getInstance().vocabularySchemas().get(vocabulary.getSchemaId());
        Optional<FieldDefinition> searchField = schema.getDefinitions()
                .stream()
                .filter(d -> d.getName().equals(entry.getContentSearch()))
                .findFirst();

        if (searchField.isEmpty()) {
            return;
        }
        List<ExtendedVocabularyRecord> results = VocabularyAPIManager.getInstance()
                .vocabularyRecords()
                .list(vocabulary.getId())
                .search(searchField.get().getId() + ":" + value)
                .all()
                .request()
                .getContent()
                .stream()
                .filter(r -> isExactMatch(r, searchField.get(), value))
                .toList();

        if (results.isEmpty()) {
            return;
        }

        if (results.size() != 1) {
            log.warn("No unique result found, using first result");
        }

        ExtendedVocabularyRecord result = results.get(0);

        // after record was loaded, get the normed value
        String fieldTo = entry.getFieldTo();
        String fieldToDynamic = entry.getFieldToDynamic();

        // if a fieldToDynamic is defined, get it from the vocabulary record
        if (!StringUtils.isEmpty(fieldToDynamic)) {
            fieldTo = result.getFieldValueForDefinitionName(fieldToDynamic).orElse(fieldTo);
        }

        String contentAuthority = result.getFieldValueForDefinitionName(entry.getContentAuthority()).orElse(null);
        String contentAuthorityUri = result.getFieldValueForDefinitionName(entry.getContentAuthorityUri()).orElse(null);
        String contentAuthorityValueUri = result.getFieldValueForDefinitionName(entry.getContentAuthorityValueUri()).orElse(null);

        // try to get a better URL from Viaf from the original URL
        if (contentAuthorityValueUri != null && !contentAuthorityValueUri.isEmpty() && contentAuthorityUri.contains("https://viaf.org")) {
            contentAuthorityValueUri = getPreferedViafId(contentAuthorityValueUri);
        }

        // now run through all fields to find the right one where to put the replaced value to
        Optional<ExtendedFieldInstance> replacementField = result.getFieldForDefinitionName(entry.getContentReplace());
        if (replacementField.isPresent()) {
            List<String> replacementValues = replacementField.get()
                    .getExtendedValues()
                    .stream()
                    .flatMap(v -> v.getTranslations().stream())
                    .map(TranslationInstance::getValue)
                    .toList();

            for (String replacementValue : replacementValues) {
                Metadata md = new Metadata(prefs.getMetadataTypeByName(fieldTo));
                md.setValue(replacementValue);

                // if an authority value url is given in the vocabulary take this
                if (!StringUtils.isEmpty(contentAuthorityValueUri)) {
                    md.setAuthorityID(contentAuthority);
                    md.setAuthorityURI(contentAuthorityUri);
                    md.setAuthorityValue(contentAuthorityValueUri);
                } else {
                    // if not authority is contained in the vocabulary take it from the original record
                    md.setAuthorityID(originalMetadata.getAuthorityID());
                    md.setAuthorityURI(originalMetadata.getAuthorityURI());
                    md.setAuthorityValue(originalMetadata.getAuthorityValue());
                }
                resultList.add(md);
            }
        }
    }

    private boolean isExactMatch(ExtendedVocabularyRecord r, FieldDefinition fieldDefinition, String value) {
        return r.getFieldForDefinition(fieldDefinition)
                .map(extendedFieldInstance -> extendedFieldInstance.getValues()
                        .stream()
                        .flatMap(v -> v.getTranslations().stream())
                        .anyMatch(t -> value.equals(t.getValue())))
                .orElse(false);
    }

    /**
     * Method to get the URL for a viaf record from the preferred institution
     *
     * @param oldUrl the main viaf entry url
     * @return String with the url of the individual preferred institution (e.g. from the LOC)
     */
    public String getPreferedViafId(String oldId) {
        MarcRecord recordToImport = NormDataImporter.getSingleMarcRecord("https://viaf.org/viaf/" + oldId + "/marc21.xml");
        List<String> databases = new ArrayList<>();
        databases.add("j9u");
        databases.add("lc");
        for (String database : databases) {
            if (recordToImport != null && recordToImport.getAuthorityDatabaseUrls() != null) {
                for (DatabaseUrl url : recordToImport.getAuthorityDatabaseUrls()) {
                    if (url.getDatabaseCode().equalsIgnoreCase(database)) {
                        String result = url.getMarcRecordUrl();
                        result = result.substring(result.indexOf("processed"));
                        return result;
                    }
                }
            }
        }
        // if no better URL could be found give back the original again
        return oldId;
    }

    @Override
    public String getPagePath() {
        return null;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null; //NOSONAR
    }

    @Data
    private class ReplacementConfiguration implements Serializable {

        private static final long serialVersionUID = 3079270612916051314L;
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
    private class ReplacementEntry implements Serializable {
        private static final long serialVersionUID = -5685314086466688260L;
        private String fieldFrom;
        private String fieldTo;
        private String fieldToDynamic;
        private String metadataGroup;
        private String vocabulary;
        private String contentSearch;
        private String contentReplace;
        private String contentAuthority;
        private String contentAuthorityUri;
        private String contentAuthorityValueUri;
        private boolean duplicateIfMissing = false;
        private boolean deleteExistingFieldTo = true;
        private boolean removeDuplicatedFieldTo = false;
        private String metadataDelimiter;
        private String vocabularyDelimiter;

        public ReplacementEntry(HierarchicalConfiguration sub) {
            fieldFrom = sub.getString("fieldFrom");
            fieldTo = sub.getString("fieldTo");
            fieldToDynamic = sub.getString("fieldToDynamic");
            vocabulary = sub.getString("vocabulary");
            contentSearch = sub.getString("contentSearch");
            contentReplace = sub.getString("contentReplace");
            contentAuthority = sub.getString("contentAuthority");
            contentAuthorityUri = sub.getString("contentAuthorityUri");
            contentAuthorityValueUri = sub.getString("contentAuthorityValueUri");
            duplicateIfMissing = sub.getBoolean("duplicateIfMissing", false);
            deleteExistingFieldTo = sub.getBoolean("deleteExistingFieldTo", true);
            removeDuplicatedFieldTo = sub.getBoolean("removeDuplicatedFieldTo", false);
            metadataDelimiter = sub.getString("metadataDelimiter", "");
            vocabularyDelimiter = sub.getString("vocabularyDelimiter", "");
            metadataGroup = sub.getString("metadataGroup", null);
        }
    }

}
