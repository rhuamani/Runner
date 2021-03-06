package edu.umass.cs.runner.system.backend.known.mturk;

import com.amazonaws.mturk.requester.Assignment;
import com.amazonaws.mturk.requester.AssignmentStatus;
import com.amazonaws.mturk.requester.HIT;
import com.amazonaws.mturk.service.axis.RequesterService;
import com.amazonaws.mturk.service.exception.InternalServiceException;
import com.amazonaws.mturk.service.exception.ObjectAlreadyExistsException;
import com.amazonaws.mturk.service.exception.ObjectDoesNotExistException;
import com.amazonaws.mturk.util.PropertiesClientConfig;
import edu.umass.cs.runner.system.backend.AbstractResponseManager;
import edu.umass.cs.runner.Record;
import edu.umass.cs.runner.Runner;
import edu.umass.cs.runner.system.backend.ITask;
import edu.umass.cs.runner.system.Parameters;
import edu.umass.cs.runner.system.SurveyResponse;
import edu.umass.cs.surveyman.qc.QCMetrics;
import edu.umass.cs.surveyman.qc.classifiers.EntropyClassifier;
import edu.umass.cs.surveyman.qc.classifiers.LogLikelihoodClassifier;
import edu.umass.cs.surveyman.survey.Survey;
import edu.umass.cs.surveyman.survey.exceptions.SurveyException;
import org.apache.logging.log4j.Logger;
import org.dom4j.DocumentException;
import org.xml.sax.SAXException;
import java.io.IOException;
import java.text.*;
import java.util.*;

import javax.xml.parsers.ParserConfigurationException;
import static java.text.MessageFormat.*;

public class MturkResponseManager extends AbstractResponseManager {

    protected static class CreateHITException extends SurveyException {
        public CreateHITException(String title) {
            super(String.format("Unable to create HIT for survey \"%s\"", title));
        }
    }

    private static final Logger LOGGER = Runner.LOGGER;
    protected final PropertiesClientConfig config;
    protected final RequesterService service;
    final protected static long maxAutoApproveDelay = 2592000l;
    final protected static long minExpirationIncrementInSeconds = 60l;
    final protected static long maxExpirationIncrementInSeconds = 31536000l;
    final protected static int maxWaitTimeInSeconds = 120;

    public MturkResponseManager(
            MturkLibrary lib)
    {
        this.config = new PropertiesClientConfig(lib.CONFIG);
        this.config.setServiceURL(lib.MTURK_URL);
        this.service = new RequesterService(config);
    }

    private static boolean overTime(
            String name,
            int waittime)
    {
        if (waittime > MturkResponseManager.maxwaittime){
          LOGGER.warn(String.format("Wait time in %s has exceeded max wait time. Cancelling request.", name));
          return true;
        } else return false;
    }

    public ITask getTask(
            String taskId)
    {
        String name = "getTask";
        int waittime = 2;
        while (true) {
            synchronized (service) {
                try {
                    HIT hit = service.getHIT(taskId);
                    LOGGER.info(String.format("Retrieved HIT %s", hit.getHITId()));
                    return new MturkTask(hit);
                } catch (InternalServiceException ise) {
                    if (overTime(name, waittime)) {
                        LOGGER.error(String.format("%s ran over time", name));
                        return null;
                    }
                    LOGGER.warn(format("{0} {1}", name, ise));
                    chill(waittime);
                    waittime *= 2;
                } catch (ObjectDoesNotExistException odnee) {
                    LOGGER.warn(format("{0} {1}", name, odnee));
                }
            }
        }
    }

    private List<Assignment> getAllAssignmentsForHIT(
            HIT hit)
    {
        String name = "getAllAssignmentsForHIT";
        int waittime = 2;
        while (true) {
            synchronized (service) {
                try {
                    Assignment[] hitAssignments = service.getAllAssignmentsForHIT(hit.getHITId());
                    List<Assignment> assignments = new LinkedList<Assignment>();
                    boolean addAll = assignments.addAll(Arrays.asList(hitAssignments));
                    if (addAll)
                        LOGGER.info(String.format("Retrieved %d assignments for HIT %s", hitAssignments.length, hit.getHITId()));
                    return assignments;
                } catch (InternalServiceException ise) {
                  LOGGER.warn(format("{0} {1}", name, ise));
                  chill(waittime);
                  waittime *= 2;
                }
            }
        }
    }

    @Override
    public boolean makeTaskAvailable(
            String taskId,
            Record record)
    {
        String name = "makeTaskAvailable";
        int waitTime = 1;
        while (true){
            try {
                MturkTask task = (MturkTask) getTask(taskId);
                int currentMaxAssignments = service.getAllAssignmentsForHIT(task.getTaskId()).length;
                int maxAssignmentsIncrement = Integer.parseInt(record.library.props.getProperty(Parameters.NUM_PARTICIPANTS)) - currentMaxAssignments;
                long expirationIncrementMillis = task.hit.getExpiration().getTimeInMillis() - System.currentTimeMillis();
                service.extendHIT(taskId, maxAssignmentsIncrement, expirationIncrementMillis / 1000);
                return true;
            } catch (InternalServiceException ise) {
                LOGGER.warn(format("{0} {1}", name, ise));
                if (overTime(name, waitTime))
                    return false;
                chill(waitTime);
                waitTime = 2 * waitTime;
            }
        }
    }

    @Override
    public void awardBonus(
            double amount,
            edu.umass.cs.surveyman.analyses.SurveyResponse sr,
            Survey survey)
    {
        String name = "awardBonus";
        int waitTime = 1;
        while (true){
            try {
                Record r = getRecord(survey);
                Runner.LOGGER.info("All tasks for this record:" + r.getAllTasks().length);
                if (r.getAllTasks().length==0){
                    Runner.LOGGER.info("No tasks for record " + r.rid);
                    return;
                }
                for (ITask task : r.getAllTasks()) {
                    Assignment[] assignments = service.getAllAssignmentsForHIT(task.getTaskId());
                    Runner.LOGGER.info("all assignments for this record:" + assignments.length);
                    String assignmentId = "";
                    for (Assignment a : assignments) {
                        if (a.getWorkerId().equals(sr.getSrid())) {
                            service.grantBonus(sr.getSrid(), amount, assignmentId, "For partial work completed.");
                            Runner.LOGGER.info(String.format("Granted worker %s bonus %f for assignment %s in survey %s"
                                    , sr.getSrid(), amount, assignmentId, survey.sourceName));
                        }
                    }
                }
            } catch (InternalServiceException ise) {
                LOGGER.warn(format("{0} {1}", name, ise));
                if (overTime(name, waitTime)){

                    return;
                }
                chill(waitTime);
                waitTime = 2 * waitTime;
            } catch (SurveyException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public ITask makeTaskForId(
            Record record,
            String taskid)
    {
        HIT task = service.getHIT(taskid);
        return new MturkTask(task, record);
    }

    @Override
    public SurveyResponse parseResponse(
            String workerId,
            String ansXML,
            Survey survey,
            Record r,
            Map<String, String> otherValues)
            throws SurveyException
    {
        try {
            if (otherValues==null)
                return new SurveyResponse(survey, workerId, ansXML, r, new HashMap<String, String>());
        else return new SurveyResponse(survey, workerId, ansXML, r, otherValues);
        } catch (DocumentException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (SAXException e) {
            e.printStackTrace();
        } catch (ParserConfigurationException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public boolean makeTaskUnavailable(
            ITask task)
    {
        String name = "expireHIT";
        while (true){
            synchronized (service) {
                try{
                    service.forceExpireHIT(task.getTaskId());
                    return true;
                }catch(InternalServiceException ise){
                  LOGGER.warn(MessageFormat.format("{0} {1}", name, ise));
                  chill(1);
                }catch(ObjectDoesNotExistException odne) {
                  LOGGER.warn(MessageFormat.format("{0} {1}", name, odne));
                  return false;
                }
            }
        }
    }

    protected String getWebsiteURL()
    {
        String name = "getWebsiteURL";
        synchronized (service) {
            while(true) {
                try {
                    return service.getWebsiteURL();
                } catch (InternalServiceException ise) {
                    LOGGER.warn(MessageFormat.format("{0} {1}", name, ise));
                  chill(3);
                }
            }
        }
    }

    protected String createHIT(
            String title,
            String description,
            String keywords,
            String xml,
            double reward,
            long assignmentDuration,
            long maxAutoApproveDelay,
            long lifetime,
            int assignments,
            String hitTypeId)
            throws ParseException,
            SurveyException
    {
        Runner.LOGGER.info("WebsiteURL:\t"+getWebsiteURL());
        System.out.println(getWebsiteURL());
        String name = "createHIT";
        int waittime = 1;
        synchronized (service) {
            while(true) {
                try {
                    HIT hitid = service.createHIT(hitTypeId
                            , title
                            , description
                            , keywords
                            , xml
                            , reward
                            , assignmentDuration
                            , maxAutoApproveDelay
                            , lifetime
                            , assignments
                            , ""
                            , null // Qualification requirements
                            , null
                        );
                    return hitid.getHITId();
                } catch (InternalServiceException ise) {
                    String info = MessageFormat.format("{0} {1}", name, ise);
                    LOGGER.info(info);
                    System.out.println(info);
                    if (overTime(name, waittime)) {
                      throw new CreateHITException(title);
                    }
                    chill(waittime);
                    waittime *= 2;
                } catch (ObjectAlreadyExistsException e) {
                    LOGGER.info(MessageFormat.format("{0} {1}", name, e));
                    chill(waittime);
                    waittime *= 2;
                }
            }
        }
    }

    public boolean renewIfExpired(String hitId, Survey survey) throws SurveyException {
        HIT hit = ((MturkTask) getTask(hitId)).hit;
        Record record;
        try {
            record = getRecord(survey);
        } catch (IOException io) {
            io.printStackTrace();
            return false;
        }
        if (hit.getExpiration().before(Calendar.getInstance())) {
            makeTaskAvailable(hitId, record);
            return true;
        } else return false;
    }

    public int numAvailableAssignments(ITask task) {
        String name = "availableAssignments";
        while (true){
            synchronized (service) {
                try{
                    HIT hit = service.getHIT(task.getTaskId());
                    return hit.getNumberOfAssignmentsAvailable();
                }catch(InternalServiceException ise){
                    LOGGER.warn(MessageFormat.format("{0} {1}", name, ise));
                    chill(1);
                }catch(ObjectDoesNotExistException odne) {
                    LOGGER.warn(MessageFormat.format("{0} {1}", name, odne));
                    return 0;
                }
            }
        }
    }

    public ITask addAssignments(ITask task, int n) {
        Class name = new Object(){}.getClass();
        int waittime = 1;
        while (true){
            synchronized (service) {
                try{
                    String id = task.getTaskId();
                    service.extendHIT(id, n, minExpirationIncrementInSeconds);
                    return task;
                }catch(InternalServiceException ise){
                    LOGGER.warn(MessageFormat.format("{0} {1}", name, ise));
                    if (waittime > maxWaitTimeInSeconds) {
                        String msg = String.format("WARNING: Exceeded max wait time in %s.%s..."
                                , name.getEnclosingClass().getName()
                                , name.getEnclosingMethod().getName());
                        LOGGER.warn(msg);
                        System.err.println(msg);
                    }
                    chill(waittime);
                    waittime *= 2;
                }catch(ObjectDoesNotExistException odne) {
                    LOGGER.warn(MessageFormat.format("{0} {1}", name, odne));
                    return null;
                }
            }
        }
    }

    private boolean approveAssignment(String assignmentId) {
        Class clz = new Object(){}.getClass();
        int waittime = 1;
        while (true) {
            synchronized (service) {
                try {
                    service.approveAssignment(assignmentId, "Thank you.");
                    return true;
                } catch (InternalServiceException ise) {
                    if (waittime > maxWaitTimeInSeconds) {
                        String msg = String.format("WARNING: Exceeded max wait time in %s.%s..."
                                , clz.getEnclosingClass().getName()
                                , clz.getEnclosingMethod().getName());
                        LOGGER.warn(msg);
                        System.err.println(msg);
                    }
                    chill(waittime);
                    waittime *= 2;
                } catch(ObjectDoesNotExistException odne) {
                    LOGGER.warn(MessageFormat.format("{0} {1}", clz, odne));
                    return false;
                }
            }
        }
    }

    private boolean isValid(
            SurveyResponse surveyResponse,
            Record record)
            throws SurveyException
    {
        String classname = record.classifier.getClass().getName();
        if (classname.equals(LogLikelihoodClassifier.class.getName()))
                return record.classifier.classifyResponse(surveyResponse);
        else if (classname.equals(EntropyClassifier.class.getName()))
                return record.classifier.classifyResponse(surveyResponse);
        else throw new RuntimeException("Unknown classifier: "+classname);
    }

    public int addResponses(
            Survey survey,
            ITask task)
            throws SurveyException
    {
        boolean success = false;
        Record record = null;
        try {
            record = AbstractResponseManager.getRecord(survey);
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (record == null) return -1;
        // local vars
        SimpleDateFormat format = new SimpleDateFormat(SurveyResponse.dateFormat);
        int validResponsesToAdd = 0, botResponsesToAdd = 0;

        while (!success) {
            HIT hit = ((MturkTask) task).hit;
            List<Assignment> assignments = getAllAssignmentsForHIT(hit);
            for (Assignment a : assignments) {
                synchronized (record) {
                    if (a.getAssignmentStatus().equals(AssignmentStatus.Submitted)) {
                        Map<String, String> otherValues = new HashMap<String, String>();
                        otherValues.put("acceptTime", String.format("%s", format.format(a.getAcceptTime().getTime())));
                        otherValues.put("submitTime", String.format("%s", format.format(a.getSubmitTime().getTime())));
                        SurveyResponse sr = parseResponse(a.getWorkerId(), a.getAnswer(), survey, record, otherValues);
                        assert !sr.otherValues.isEmpty();
                        boolean valid = isValid(sr, record);
                        LOGGER.debug(String.format("Response %s valid: %b", sr.getSrid(), valid));
                        assert valid == (sr.getScore() >= sr.getThreshold());
                        if (valid) {
                            record.addValidResponse(sr);
                            record.removeBotResponse(sr);
                            validResponsesToAdd++;
                        } else {
                            record.addBotResponse(sr);
                            record.removeValidResponse(sr);
                            botResponsesToAdd++;
                        }
                        approveAssignment(a.getAssignmentId());
                    }
                }
            }
            success=true;
        }
        if (validResponsesToAdd>0 || botResponsesToAdd> 0)
            LOGGER.info(String.format("%d responses total. %d valid responses added. %d invalid responses added."
                    , record.getNumValidResponses()+record.getNumBotResponses(), validResponsesToAdd, botResponsesToAdd));
        return validResponsesToAdd;
    }
}
