package de.intranda.goobi.plugins;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;
import org.goobi.vocabulary.Field;
import org.goobi.vocabulary.VocabRecord;

import de.intranda.digiverso.normdataimporter.NormDataImporter;
import de.intranda.digiverso.normdataimporter.model.MarcRecord;
import de.intranda.digiverso.normdataimporter.model.MarcRecord.DatabaseUrl;
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
                String[] splitValues = new String[]{value};
                if (entry.metadataDelimiter != null && entry.metadataDelimiter.length() > 0 ) {
                    splitValues = value.split(entry.metadataDelimiter);
                }
                
                // for each value generate new metadata
                for (String splittedValue : splitValues) {
                    try {
                    	// get normed value from configured vocabulary
                        // newMetadata.setValue(getNormedValue(splittedValue.trim(), entry));
                        // Metadata newMetadata = new Metadata(prefs.getMetadataTypeByName(entry.getFieldTo()));
                        
                        List <Metadata> newListMd = getNormedMetadata(splittedValue.trim(), entry, prefs, md);
                        for (Metadata newMetadata : newListMd) {
                            docstruct.addMetadata(newMetadata);
                        }
                    } catch (MetadataTypeNotAllowedException e) {
                        log.error(e);
                    }
                }
            }
        }
    }

    private List <Metadata> getNormedMetadata(String value, ReplacementEntry entry, Prefs prefs, Metadata originalMetadata) throws MetadataTypeNotAllowedException {
    	List <Metadata> listMd = new ArrayList<Metadata>();
        
        // search for a record containing the search value
    	List<VocabRecord> records =  VocabularyManager.findRecords(entry.getVocabulary(), value, entry.getContentSearch());
        if (records != null && !records.isEmpty()) {
            // first load the entire record again with all fields from vocabulary
        	VocabRecord myRecord = VocabularyManager.getRecord(records.get(0).getVocabularyId(), records.get(0).getId());
        	List<Field> fields = myRecord.getFields();
            
            // after record was loaded, get the normed value
            String fieldTo = entry.getFieldTo();
            String fieldToDynamic = entry.getFieldToDynamic();
            String contentAuthority = null;
            String contentAuthorityUri = null;
            String contentAuthorityValueUri = null;
            
            // if a fieldToDynamic is defined, get it from the vocabulary record
            if (!StringUtils.isEmpty(fieldToDynamic)) {
            	for (Field field : fields) {
                    if (field.getLabel().equals(fieldToDynamic)) {
                    	fieldTo = field.getValue();
                    }
                }
            }
            
            // run through all fields to collect the authority data
        	for (Field field : fields) {
        		if (field.getLabel().equals(entry.getContentAuthority())) {
                	contentAuthority = field.getValue();
                }
        		if (field.getLabel().equals(entry.getContentAuthorityUri())) {
                	contentAuthorityUri = field.getValue();
                }
        		if (field.getLabel().equals(entry.getContentAuthorityValueUri())) {
                	contentAuthorityValueUri = field.getValue();
                }
            }
        	
        	// try to get a better URL from Viaf from the original URL
        	if (contentAuthorityValueUri != null && !contentAuthorityValueUri.isEmpty() && contentAuthorityUri.contains("https://viaf.org")) {
        		contentAuthorityValueUri = getPreferedViafId(contentAuthorityValueUri);
        	}
           
            // now run through all fields to find the right one where to put the replaced value to
            for (Field field : fields) {
                if (field.getLabel().equals(entry.getContentReplace())) {
                	
                    // split the content at delimiter to separate values
                    String[] splitContent = new String[]{field.getValue()};
                    if (entry.vocabularyDelimiter != null && entry.vocabularyDelimiter.length() > 0 ) {
                        splitContent = field.getValue().split(entry.vocabularyDelimiter);
                    }
                    
                    // now run through all split content of vocabulary field to create multiple metadata elements
                    for (String con : splitContent) {
                        Metadata md = new Metadata(prefs.getMetadataTypeByName(fieldTo));
                        md.setValue(con);
                        
                        // if an authority value url is given in the vocabulary take this
                        if(!StringUtils.isEmpty(contentAuthorityValueUri)) {
                            md.setAuthorityID(contentAuthority);
                            md.setAuthorityURI(contentAuthorityUri);
                            md.setAuthorityValue(contentAuthorityValueUri);
                        } else {
                            // if not authority is contained in the vocabulary take it from the original record
                            md.setAuthorityID(originalMetadata.getAuthorityID());
                            md.setAuthorityURI(originalMetadata.getAuthorityURI());
                            md.setAuthorityValue(originalMetadata.getAuthorityValue());
                        }
                        listMd.add(md); 
                    }
                }
            }
        }
        
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

    
	public static void main(String[] args) {
	    //YerushaMetadataReplacementPlugin ymrp = new YerushaMetadataReplacementPlugin();
		//System.out.println(ymrp.getPreferedViafId("90722334"));
		//System.out.println(ymrp.getPreferedViafId("http://viaf.org/viaf/90722334"));
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
			if (recordToImport!=null && recordToImport.getAuthorityDatabaseUrls() != null) {
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
        private String fieldFrom;
        private String fieldTo;
        private String fieldToDynamic;
        private String vocabulary;
        private String contentSearch;
        private String contentReplace;
        private String contentAuthority;
        private String contentAuthorityUri;
        private String contentAuthorityValueUri;
        private boolean duplicateIfMissing = false;
        private boolean deleteExistingFieldTo = true;
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
            metadataDelimiter = sub.getString("metadataDelimiter", "");
            vocabularyDelimiter = sub.getString("vocabularyDelimiter", "");
        }
    }
 
}
