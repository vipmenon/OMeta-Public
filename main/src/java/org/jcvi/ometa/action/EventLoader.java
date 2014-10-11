/*
 * Copyright J. Craig Venter Institute, 2013
 *
 * The creation of this program was supported by J. Craig Venter Institute
 * and National Institute for Allergy and Infectious Diseases (NIAID),
 * Contract number HHSN272200900007C.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.jcvi.ometa.action;

import com.opensymphony.xwork2.ActionSupport;
import com.opensymphony.xwork2.Preparable;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.jcvi.ometa.bean_interface.ProjectSampleEventWritebackBusiness;
import org.jcvi.ometa.db_interface.ReadBeanPersister;
import org.jcvi.ometa.engine.MultiLoadParameter;
import org.jcvi.ometa.helper.AttributeHelper;
import org.jcvi.ometa.helper.AttributePair;
import org.jcvi.ometa.model.*;
import org.jcvi.ometa.stateless_session_bean.DetailedException;
import org.jcvi.ometa.stateless_session_bean.ForbiddenResourceException;
import org.jcvi.ometa.utils.CommonTool;
import org.jcvi.ometa.utils.Constants;
import org.jcvi.ometa.utils.TemplatePreProcessingUtils;
import org.jcvi.ometa.utils.UploadActionDelegate;
import org.jcvi.ometa.validation.DPCCValidator;
import org.jtc.common.util.property.PropertyHelper;

import javax.naming.InitialContext;
import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.UserTransaction;
import java.io.File;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.*;

/**
 * Created by IntelliJ IDEA.
 * User: hkim
 * Date: 6/30/11
 * Time: 9:27 AM
 * To change this template use File | Settings | File Templates.
 */
public class EventLoader extends ActionSupport implements Preparable {
    private ReadBeanPersister readPersister;
    private ProjectSampleEventWritebackBusiness psewt;

    private List<Project> projectList;
    private String projectNames;

    private String projectName;
    private String sampleName;
    private String eventName;
    private Long projectId;
    private Long eventId;

    private String status;
    private String jobType;
    private String label;

    private Project loadingProject;
    private Sample loadingSample;
    private List<FileReadAttributeBean> beanList;
    private List<GridBean> gridList;

    private InputStream downloadStream;
    private String downloadContentType;

    private File dataTemplate;
    private String dataTemplateFileName;
    private String dataTemplateContentType;

    private String fileStoragePath;
    private ArrayList<String> loadedFiles;

    private String ids;

    private static final String DEFAULT_USER_MESSAGE = "Not yet entered";
    private final String MULTIPLE_SUBJECT_IN_FILE_MESSAGE = "Multiple projects are found in the file";
    private final String UNSUPPORTED_UPLOAD_FILE_TYPE_MESSAGE = "File type is not supported. Supported file types are JPG, JPEG, GIF and BMP.";
    private String message = DEFAULT_USER_MESSAGE;

    private Logger logger = Logger.getLogger(EventLoader.class);

    public EventLoader(ReadBeanPersister persister, ProjectSampleEventWritebackBusiness writeEjb) {
        this.readPersister = persister;
        this.psewt = writeEjb;
    }

    public EventLoader() {
        Properties props = PropertyHelper.getHostnameProperties(Constants.PROPERTIES_FILE_NAME);
        readPersister = new ReadBeanPersister(props);

        fileStoragePath = props.getProperty(Constants.CONIFG_FILE_STORAGE_PATH); //file storage area

        UploadActionDelegate udelegate = new UploadActionDelegate();
        psewt = udelegate.initializeBusinessObject(logger, psewt);
    }

    @Override
    public void prepare() throws Exception {
        //get project list for the drop down box
        List<String> projectNameList = new ArrayList<String>();
        if (projectNames == null || projectNames.equals("")) {
            projectNameList.add("ALL");
        } else if (projectNames.contains(",")) {
            projectNameList.addAll(Arrays.asList(projectNames.split(",")));
        } else {
            projectNameList.add(projectNames);
        }
        projectList = readPersister.getProjects(projectNameList);
    }

    /**
     * Setup a download filename to fully-indicate type of event.  See also: struts.xml
     */
    public String getDownloadFileName() {
        return eventName + "_template." + (jobType.endsWith("e") ? "xls" : "csv");
    }

    public String execute() {
        String rtnVal = SUCCESS;
        UserTransaction tx = null;

        try {
            this.sampleName = (this.sampleName!=null && this.sampleName.equals("0") ? null : this.sampleName);

            if (jobType != null) {
                boolean isProjectRegistration = eventName.contains(Constants.EVENT_PROJECT_REGISTRATION);
                boolean isSampleRegistration = eventName.contains(Constants.EVENT_SAMPLE_REGISTRATION);

                if(this.projectName==null || this.projectName.equals("0") || eventName==null || eventName.equals("0"))
                    throw new Exception("Project or Event type is not selected.");

                if (jobType.equals("insert")) { //loads single event
                    tx = (UserTransaction) new InitialContext().lookup("java:comp/UserTransaction");
                    tx.begin();

                    gridList = null; // force grid list to be empty
                    MultiLoadParameter loadParameter = new MultiLoadParameter();
                    psewt.loadAll(null, this.createMultiLoadParameter(loadParameter, loadingProject, loadingSample, beanList, 1));

                    this.pageDataReset(isProjectRegistration, isSampleRegistration, this.status);

                    addActionMessage("Event has been loaded successfully.");
                } else if(jobType.equals("grid")) { //loads multiple events from grid view
                    tx = (UserTransaction) new InitialContext().lookup("java:comp/UserTransaction");
                    tx.begin();

                    MultiLoadParameter loadParameter = new MultiLoadParameter();

                    int gridRowIndex = 0;
                    for(GridBean gBean : gridList) {
                        if(gBean!=null) {
                            if(isProjectRegistration && gBean.getProjectName() != null && gBean.getProjectPublic() != null) {
                                loadingProject = new Project();
                                loadingProject.setProjectName(gBean.getProjectName());
                                loadingProject.setIsPublic(Integer.valueOf(gBean.getProjectPublic()));
                            } else if(isSampleRegistration && gBean.getSampleName() != null && !gBean.getSampleName().equals("0") && gBean.getSamplePublic() != null) {
                                Sample existingSample = readPersister.getSample(this.projectId, gBean.getSampleName());
                                if(existingSample == null) {
                                    loadingSample = new Sample();
                                    loadingSample.setSampleName(gBean.getSampleName());
                                    loadingSample.setParentSampleName(gBean.getParentSampleName());
                                    loadingSample.setIsPublic(Integer.valueOf(gBean.getSamplePublic()));
                                } else {
                                    loadingSample = existingSample;
                                }
                            } else {
                                if(gBean.getSampleName() != null) {
                                    this.sampleName = gBean.getSampleName();
                                }
                            }

                            // process empty attribute lists events for project/sample registrations
                            if((gBean.getBeanList() != null && gBean.getBeanList().size() > 0)
                                    || this.eventName.contains(Constants.EVENT_PROJECT_REGISTRATION)
                                    || this.eventName.contains(Constants.EVENT_SAMPLE_REGISTRATION)) {
                                this.createMultiLoadParameter(loadParameter, loadingProject,  loadingSample, gBean.getBeanList(), ++gridRowIndex);
                            }
                        }
                    }

                    psewt.loadAll(null, loadParameter);

                    this.pageDataReset(isProjectRegistration, isSampleRegistration, this.status);

                    addActionMessage("Events have been loaded successfully.");
                } else if (jobType.equals("file")) { //loads data from a CSV file to grid view
                    if(!this.dataTemplate.canRead()) {
                        throw new Exception("Error in reading the file.");
                    } else {
                        try {
                            TemplatePreProcessingUtils templateUtil = new TemplatePreProcessingUtils();
                            gridList = templateUtil.parseEventFile(
                                    this.dataTemplateFileName, this.dataTemplate,
                                    this.projectName, isProjectRegistration, isSampleRegistration
                            );
                            jobType = "grid";
                        } catch(Exception ex) {
                            throw ex;
                        }
                    }
                } else if(jobType.startsWith("template")) { //download template
                    List<EventMetaAttribute> emaList = this.readPersister.getEventMetaAttributes(this.projectName, this.eventName);
                    emaList = CommonTool.filterActiveEventMetaAttribute(emaList);
                    CommonTool.sortEventMetaAttributeByOrder(emaList);

                    /*
                     * removing the sanity check on sample requirement since multiple sample support is in action
                     * by hkim 5/2/13
                    ModelValidator validator = new ModelValidator();
                    validator.validateEventTemplateSanity(emaList, projectName, sampleName, eventName);
                    */

                    TemplatePreProcessingUtils templateUtil = new TemplatePreProcessingUtils();
                    String templateType = this.jobType.substring(jobType.indexOf("_")+1);
                    this.downloadStream = templateUtil.buildFileContent(templateType, emaList, this.projectName, this.sampleName, this.eventName);
                    this.downloadContentType = "application/octet-stream"; //templateType.equals("e") ? "application/vnd.ms-excel" : "text/csv";

                    if(ids != null && !ids.isEmpty()) { //project or sample edit from EventDetail
                        StringBuffer dataBuffer = new StringBuffer();

                        AttributeHelper attributeHelper = new AttributeHelper(this.readPersister);
                        List<AttributePair> pairList = attributeHelper.getAllAttributeByIDs(this.projectId, this.eventId, this.ids, "s");
                        if(pairList != null) {
                            for(AttributePair pair : pairList) {
                                dataBuffer.append(pair.getProjectName() + ",");
                                Sample currSample = pair.getSample();
                                dataBuffer.append(currSample.getSampleName() + ",");
                                if(isSampleRegistration) {
                                    dataBuffer.append(currSample.getParentSampleName() + ",");
                                    dataBuffer.append(currSample.getIsPublic() + ",");
                                }
                                List<FileReadAttributeBean> attributeList = pair.getAttributeList();
                                Map<String, String> attributeMap = AttributeHelper.attributeListToMap(attributeList);

                                for(EventMetaAttribute ema : emaList) {
                                    String attributeName = ema.getLookupValue().getName();
                                    dataBuffer.append(attributeMap.containsKey(attributeName) ? attributeMap.get(attributeName) : "");
                                    dataBuffer.append(",");
                                }
                                dataBuffer.append("\n");
                            }
                        }

                        StringBuffer newTemplateBuffer = new StringBuffer();
                        List<String> templateLines = IOUtils.readLines(this.downloadStream);
                        for(int i = 0;i < 2;i++) { //only writes column headers and descriptions
                            newTemplateBuffer.append(templateLines.get(i)).append("\n");
                        }
                        newTemplateBuffer.append(dataBuffer);
                        this.downloadStream = IOUtils.toInputStream(newTemplateBuffer.toString());
                    }
                    rtnVal = Constants.FILE_DOWNLOAD_MSG;
                } else if(jobType.equals("projectedit")) {
                    AttributeHelper attributeHelper = new AttributeHelper(this.readPersister);
                    if(this.eventId == null && this.eventName != null) {
                        LookupValue eventLV = this.readPersister.getLookupValue(this.eventName, Constants.EVENT_TYPE_LV_TYPE_NAME);
                        if(eventLV != null) {
                            this.eventId = eventLV.getLookupValueId();
                        }
                    }
                    List<AttributePair> projectPairList = attributeHelper.getAllAttributeByIDs(this.projectId, this.eventId, "" + this.projectId, "p");
                    if(projectPairList.size() > 0) {
                        AttributePair projectPair = projectPairList.get(0);
                        this.beanList = projectPair.getAttributeList();
                    }
                    jobType = "form";
                }
            }

            if(ids != null && ids.length() > 0) {
                jobType = "grid";
            }
        } catch (Exception ex) {

            if(loadedFiles!=null && loadedFiles.size()>0) { //deletes uploaded files in event of error
                for(String filePath : loadedFiles) {
                    File tempFile = new File(fileStoragePath + filePath);
                    if(tempFile.exists())
                        tempFile.delete();
                }
            }

            try { //transaction rollback
                if(tx!=null)
                    tx.rollback();
            } catch (SystemException se) {
                addActionError(se.toString());
            }

            //<Date>:<Project>:<Sample>: <Type>:<User ID>:<Row Number>: <Data attribute Name>:<Error message>
            StringBuilder error = new StringBuilder(DateFormat.getDateTimeInstance().format(Calendar.getInstance().getTime()) + ":");
            error.append(this.projectName + ":");
            error.append((this.sampleName == null ? "" : this.sampleName) + ":");
            error.append(this.eventName + ":");

            String errorMsg = "";
            if (ex.getClass() == ForbiddenResourceException.class) {
                errorMsg = Constants.DENIED_USER_EDIT_MESSAGE;
                rtnVal = Constants.FORBIDDEN_ACTION_RESPONSE;
            } else {
                errorMsg = (ex.getClass() == DetailedException.class ? ((DetailedException)ex).getRowIndex() + ":" : "") +
                        (ex.getCause() == null ? ex.getMessage() : ex.getCause());
                rtnVal = ERROR;
            }

            addActionError(errorMsg.replaceAll("\\\n", "<br/>"));
            error.append(errorMsg);
            logger.error(error.toString());
        } finally {
            try {
                if(tx !=null && tx.getStatus() != Status.STATUS_NO_TRANSACTION) {
                    tx.commit();
                }

                if(jobType != null && jobType.equals("grid") && this.dataTemplate != null) {
                    this.dataTemplate.delete();
                    this.dataTemplate = null;
                    this.dataTemplateContentType = null;
                    this.dataTemplateFileName = null;
                }
            } catch(Exception ex) {
                ex.printStackTrace();
            }
        }

        return rtnVal;
    }

    private MultiLoadParameter createMultiLoadParameter(MultiLoadParameter loadParameter, Project project, Sample sample, List<FileReadAttributeBean> frab, int index)
            throws Exception {
        boolean isSampleRegistration = false;
        boolean isProjectRegistration = false;

        if (this.eventName.contains(Constants.EVENT_PROJECT_REGISTRATION) && project.getProjectName() != null && !project.getProjectName().isEmpty()) {
            isProjectRegistration = true;
        } else if (this.eventName.contains(Constants.EVENT_SAMPLE_REGISTRATION) && sample.getSampleName() != null && !sample.getSampleName().isEmpty()) {
            isSampleRegistration = true;
        }

        this.sampleName = isSampleRegistration ? sample.getSampleName() : this.sampleName;

        List<FileReadAttributeBean> loadingList = null;
        if (frab != null && frab.size() > 0) {
            loadingList = processFileReadBeans(
                    isProjectRegistration ? project.getProjectName() : this.projectName,
                    isSampleRegistration ? sample.getSampleName() : this.sampleName,
                    frab
            );
        }

        if (isProjectRegistration) {
            /*
            *   loads all meta attributes from the parent
            *   by hkim 6/11/13
            */
            List<EventMetaAttribute> emas = this.readPersister.getEventMetaAttributes(this.projectName, null); //, Constants.EVENT_PROJECT_REGISTRATION);
            List<EventMetaAttribute> newEmas = null;
            if (emas != null && emas.size() > 0) {
                newEmas = new ArrayList<EventMetaAttribute>(emas.size());
                for (EventMetaAttribute ema : emas) {
                    EventMetaAttribute newEma = CommonTool.createEMA(
                            null, project.getProjectName(), ema.getEventName(), ema.getAttributeName(),
                            ema.isRequired(), ema.isActive(), ema.getDataType(), ema.getDesc(),
                            ema.getOntology(), ema.getLabel(), ema.getOptions(), ema.isSampleRequired());
                    newEma.setEventTypeLookupId(ema.getEventTypeLookupId());
                    newEma.setNameLookupId(ema.getNameLookupId());
                    newEmas.add(newEma);
                }
            }

            List<SampleMetaAttribute> smas = this.readPersister.getSampleMetaAttributes(projectId);
            List<SampleMetaAttribute> newSmas = null;
            if(smas != null && smas.size() > 0) {
                newSmas = new ArrayList<SampleMetaAttribute>(smas.size());
                for(SampleMetaAttribute sma : smas) {
                    SampleMetaAttribute newSma = new SampleMetaAttribute();
                    newSma.setProjectName(project.getProjectName());
                    newSma.setAttributeName(sma.getAttributeName());
                    newSma.setNameLookupId(sma.getNameLookupId());
                    newSma.setDataType(sma.getDataType());
                    newSma.setDesc(sma.getDesc());
                    newSma.setLabel(sma.getLabel());
                    newSma.setOntology(sma.getOntology());
                    newSma.setOptions(sma.getOptions());
                    newSma.setRequired(sma.isRequired());
                    newSma.setActive(sma.isActive());
                    newSmas.add(newSma);
                }
            }

            List<ProjectMetaAttribute> pmas = this.readPersister.getProjectMetaAttributes(this.projectName);
            List<ProjectMetaAttribute> newPmas = null;
            if (pmas != null && pmas.size() > 0) {
                newPmas = new ArrayList<ProjectMetaAttribute>(pmas.size());
                for (ProjectMetaAttribute pma : pmas) {
                    ProjectMetaAttribute newPma = new ProjectMetaAttribute();
                    newPma.setProjectName(project.getProjectName());
                    newPma.setAttributeName(pma.getAttributeName());
                    newPma.setDataType(pma.getDataType());
                    newPma.setDesc(pma.getDesc());
                    newPma.setLabel(pma.getLabel());
                    newPma.setNameLookupId(pma.getNameLookupId());
                    newPma.setOntology(pma.getOntology());
                    newPma.setOptions(pma.getOptions());
                    newPma.setRequired(pma.isRequired());
                    newPma.setActive(pma.isActive());
                    newPmas.add(newPma);
                }
            }
            loadParameter.addProjectPair(feedProjectData(project), loadingList, newPmas, newSmas, newEmas, index);
        } else {
            boolean notEmptyList = loadingList != null && loadingList.size() > 0;

            if(!this.eventName.contains("Project")) { //do not update sample status for project level events
                if((this.status.equals("submit") || this.status.equals("validate")) && notEmptyList) { //DPCC data validation
                    this.validateDataForDPCC(loadingList, index);
                }

                if(isSampleRegistration) {
                    this.updateSampleStatus(loadingList, this.status, index);
                    loadParameter.addSamplePair(feedSampleData(sample), loadingList, index);
                } else {
                    if(notEmptyList)  {
                        if(this.eventName.contains(Constants.EVENT_SAMPLE_UPDATE)) {
                            this.updateSampleStatus(loadingList, this.status, index);
                        }
                        loadParameter.addEvents(this.eventName, loadingList);
                    }
                }
            }
        }
        loadParameter.setEventName(this.eventName);
        return loadParameter;
    }

    private Project feedProjectData(Project project) throws Exception {
        project.setParentProjectName(this.projectName);

        Project parentProject = readPersister.getProject(this.projectName);
        project.setParentProjectId(parentProject.getProjectId());
        project.setProjectLevel(parentProject.getProjectLevel()+1);
        project.setEditGroup(parentProject.getEditGroup());
        project.setViewGroup(parentProject.getViewGroup());
        return project;
    }

    private Sample feedSampleData(Sample sample) throws Exception {
        sample.setProjectId(this.projectId);
        sample.setProjectName(this.projectName);

        //set project level by adding 1 to selected parent project's level
        if(sample.getParentSampleName()==null || sample.getParentSampleName().equals("0")) {
            sample.setParentSampleName(null);
            sample.setParentSampleId(null);
            sample.setSampleLevel(1);
        } else {
            String parentSampleName = sample.getParentSampleName();
            if (parentSampleName != null && !parentSampleName.isEmpty() && !parentSampleName.equals("0")) {
                Sample selectedParentSample = readPersister.getSample(this.projectId, parentSampleName);
                if(selectedParentSample != null && selectedParentSample.getSampleId() != null) {
                    sample.setSampleLevel(selectedParentSample.getSampleLevel() + 1);
                    sample.setParentSampleId(selectedParentSample.getSampleId());
                }
            }
        }
        return sample;
    }

    private List<FileReadAttributeBean> processFileReadBeans(String _projectName, String _sampleName, List<FileReadAttributeBean> loadingList) throws Exception {
        List<FileReadAttributeBean> processedList = new ArrayList<FileReadAttributeBean>();
        for(FileReadAttributeBean fBean : loadingList) {
            if (fBean.getAttributeName().equals("0") || fBean.getAttributeValue() == null
                    || fBean.getAttributeValue().isEmpty() || fBean.getAttributeValue().toLowerCase().equals("null")) { //&& !fBean.getAttributeValue().equals("0")
                continue;
            }

            if(fBean.getProjectName() == null || eventName.contains(Constants.EVENT_PROJECT_REGISTRATION)) {
                fBean.setProjectName(_projectName);
            }
            if(fBean.getSampleName() == null) {
                fBean.setSampleName(_sampleName);
            }

            //handle file uploads
            if(fBean.getUpload()!=null && fBean.getUploadFileName()!=null && !fBean.getUploadFileName().isEmpty()) {
                fileStoragePath = fileStoragePath + (fileStoragePath.endsWith(File.separator)?"":File.separator);
                String originalFileName = fBean.getUploadFileName();

                String fileDirectoryPathProject = _projectName.replaceAll(" ", "_"); //project folder
                String fileDirectoryPathSample = fileDirectoryPathProject + File.separator +
                        (_sampleName!=null&&!_sampleName.isEmpty()?_sampleName.replaceAll(" ", "_"):"project"); //sample folder
                String fileDirectoryPath = fileDirectoryPathSample + File.separator + CommonTool.convertTimestampToDate(new Date()); //date folder

                String fileName = originalFileName.substring(0,originalFileName.indexOf(".")) +
                        "_"+System.currentTimeMillis() +
                        originalFileName.substring(originalFileName.indexOf(".")); //append "_" + current time in milliseconds to file name

                File fileDirectory = new File(fileStoragePath + fileDirectoryPath);
                if(!fileDirectory.exists() || !fileDirectory.isDirectory()) {
                    fileDirectory.mkdirs();
                }

                File theFile = new File(fileDirectory.getPath() + File.separator + fileName);
                FileUtils.copyFile(fBean.getUpload(), theFile);

                if(theFile.exists() && theFile.isFile() && theFile.canRead()) {
                    fBean.getUpload().delete();

                    fBean.setAttributeValue(fileDirectoryPath + File.separator + fileName);
                    if(loadedFiles==null) {
                        loadedFiles = new ArrayList<String>();
                    }
                    loadedFiles.add(fBean.getAttributeValue());
                }
            }

            processedList.add(fBean);
        }
        return processedList;
    }

    private void updateSampleStatus(List<FileReadAttributeBean> loadingList, String status, int index) throws Exception {
        boolean foundSampleStatus = false;
        String strStatus = status.equals("submit") ? Constants.DPCC_STATUS_SUBMITTED : status.equals("validate") ? Constants.DPCC_STATUS_VALIDATED : Constants.DPCC_STATUS_EDITING;

        try {
            if(this.sampleName == null || this.sampleName.isEmpty()) {
                throw new Exception("Sample is required.");
            }

            Sample sample = this.readPersister.getSample(this.projectId, this.sampleName);
            if(sample != null) { //it could be sample registration event
                List<SampleAttribute> saList = this.readPersister.getSampleAttributes(sample.getSampleId());
                for(SampleAttribute sa : saList) {
                    if(sa.getMetaAttribute().getLookupValue().getName().equals(Constants.ATTR_SAMPLE_STATUS)) {
                        if(sa.getAttributeStringValue() != null && sa.getAttributeStringValue().equals("Data submitted to DPCC")) {
                            throw new Exception("You cannot load any events for samples that has been submitted to DPCC.");
                        }
                    }
                }
            }

            for(FileReadAttributeBean fBean : loadingList) {
                if(fBean.getAttributeName().equals(Constants.ATTR_SAMPLE_STATUS)) {
                    fBean.setAttributeValue(strStatus);
                    foundSampleStatus = true;
                }
            }
            if(!foundSampleStatus) { //if sample status attribute is not in the list
                List<SampleMetaAttribute> smaList = this.readPersister.getSampleMetaAttributes(this.projectId);
                for(SampleMetaAttribute sma : smaList) { //check if sample status is in sample meta attributes
                    if(sma.getLookupValue().getName().equals(Constants.ATTR_SAMPLE_STATUS)) {
                        foundSampleStatus = true;
                    }
                }

                if(foundSampleStatus) { //manually add sample status with the status value
                    FileReadAttributeBean statusBean = new FileReadAttributeBean();
                    statusBean.setAttributeName(Constants.ATTR_SAMPLE_STATUS);
                    statusBean.setAttributeValue(strStatus);
                    statusBean.setProjectName(this.projectName);
                    statusBean.setSampleName(this.sampleName);
                    loadingList.add(statusBean);
                } else {
                    throw new Exception("'" + Constants.ATTR_SAMPLE_STATUS + "' attribute not found.");
                }
            }
        } catch(Exception ex) {
            ex.printStackTrace();
            DetailedException dex = new DetailedException(index, ex.getMessage());
            throw dex;
        }
    }

    private void validateDataForDPCC(List<FileReadAttributeBean> loadingList, int index) throws Exception {
        String attributeName = null;
        try {
            for(FileReadAttributeBean fBean : loadingList) {
                if(fBean.getAttributeName().toLowerCase().contains("date")) {
                    attributeName = fBean.getAttributeName();
                    DPCCValidator.validateDate(fBean.getAttributeValue());
                }
            }
        } catch(Exception ex) {
            DetailedException dex = new DetailedException(index, "date parse error: '" + attributeName + "'");
            throw dex;
        }
    }

    private void pageDataReset(boolean isProjectRegistration, boolean isSampleRegistration, String status) {
        boolean resetIdsAndNames = true;
        boolean resetLists = true;

        if(status.equals("save") || status.equals("validate")) {
            resetIdsAndNames = false;
            resetLists = false;
            if(isSampleRegistration) { //update registration event to update on save requests
                this.eventName = this.eventName.replaceAll(Constants.EVENT_SAMPLE_REGISTRATION, Constants.EVENT_SAMPLE_UPDATE);
            } else if(isProjectRegistration) {
                this.eventName = this.eventName.replaceAll(Constants.EVENT_PROJECT_REGISTRATION, Constants.EVENT_PROJECT_UPDATE);
            }
        }

        if(resetIdsAndNames) {
            projectId = null;
            projectName = null;
            eventId = null;
            eventName = null;
        }
        if(resetLists) {
            beanList = null;
            gridList = null;
        }
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    private String getUnknownErrorMessage() {
        return "Unknown error uploading file " + this.dataTemplateFileName;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getSampleName() {
        return sampleName;
    }

    public void setSampleName(String sampleName) {
        this.sampleName = sampleName;
    }

    public String getEventName() {
        return eventName;
    }

    public void setEventName(String eventName) {
        this.eventName = eventName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public List<Project> getProjectList() {
        return projectList;
    }

    public void setProjectList(List<Project> projectList) {
        this.projectList = projectList;
    }

    public String getProjectNames() {
        return projectNames;
    }

    public void setProjectNames(String projectNames) {
        this.projectNames = projectNames;
    }

    public List<FileReadAttributeBean> getBeanList() {
        return beanList;
    }

    public void setBeanList(List<FileReadAttributeBean> beanList) {
        this.beanList = beanList;
    }

    public Project getLoadingProject() {
        return loadingProject;
    }

    public void setLoadingProject(Project loadingProject) {
        this.loadingProject = loadingProject;
    }

    public Sample getLoadingSample() {
        return loadingSample;
    }

    public void setLoadingSample(Sample loadingSample) {
        this.loadingSample = loadingSample;
    }

    public List<GridBean> getGridList() {
        return gridList;
    }

    public void setGridList(List<GridBean> gridList) {
        this.gridList = gridList;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getEventId() {
        return eventId;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

    public InputStream getDownloadStream() {
        return downloadStream;
    }

    public void setDownloadStream(InputStream downloadStream) {
        this.downloadStream = downloadStream;
    }

    public String getDownloadContentType() {
        return downloadContentType;
    }

    public void setDownloadContentType(String downloadContentType) {
        this.downloadContentType = downloadContentType;
    }

    public void setDataTemplateContentType(String dataTemplateContentType) {
        this.dataTemplateContentType = dataTemplateContentType;
    }

    public void setDataTemplateFileName(String dataTemplateFileName) {
        this.dataTemplateFileName = dataTemplateFileName;
    }

    public void setDataTemplate(File dataTemplate) {
        this.dataTemplate = dataTemplate;
    }

    public String getIds() {
        return ids;
    }

    public void setIds(String ids) {
        this.ids = ids;
    }
}
