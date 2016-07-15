package edu.umass.cs.runner;

import clojure.lang.ArraySeq;
import edu.umass.cs.runner.system.QuestionResponse;
import edu.umass.cs.runner.system.SurveyResponse;
import edu.umass.cs.surveyman.analyses.IQuestionResponse;
import edu.umass.cs.surveyman.analyses.OptTuple;
import edu.umass.cs.surveyman.survey.HTMLDatum;
import edu.umass.cs.surveyman.survey.InputOutputKeys;
import edu.umass.cs.surveyman.survey.StringDatum;
import edu.umass.cs.surveyman.survey.Survey;
import javafx.scene.control.Cell;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.io.RuntimeIOException;
import org.supercsv.cellprocessor.constraint.NotNull;
import org.supercsv.cellprocessor.ift.CellProcessor;

import org.supercsv.io.CsvListWriter;
import org.supercsv.io.ICsvListWriter;
import org.supercsv.prefs.CsvPreference;
import org.supercsv.util.CsvContext;


import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ResponseWriter {

    public static String[] defaultHeaders = new String[]{"responseid", "workerid", "surveyid"
            , "questionid", "questiontext", "questionpos"
            , "optionid", "optiontext", "optionpos"};
    public static final String sep = ",";
    public static final String newline = "\r\n";
//    public static final CellProcessor[] defaultProcessors = new CellProcessor[] {
//            new NotNull(), // responseid
//            new NotNull(), // workerid
//            new NotNull(), // surveyid
//            new NotNull(), // questionid
//            new NotNull(), // questiontext
//            new NotNull(), // optionid
//            new NotNull(), // optiontext
//            new NotNull() // optionpos
//    };

    //new list of all processors
    public List<CellProcessor> defProcessors = new ArrayList<>();

    public final Survey survey;
    public final List<String> backendHeaders;
    public final File outputFile;
    private boolean writtenHeaders;

    public ResponseWriter(Record record) {
        this.survey = record.survey;
        this.backendHeaders = record.library.getBackendHeaders();
        this.outputFile = new File(record.outputFileName);
        try {
            //for 8 default headers
            for (int i = 0 ; i < 8 ; i++){
                defProcessors.add(new NotNull());
            }
            CsvListWriter writer = new CsvListWriter(new FileWriter(this.outputFile) , CsvPreference.STANDARD_PREFERENCE);
            writeHeaders(writer);

            this.writtenHeaders = true;

        } catch (IOException io) {
            throw new RuntimeIOException(io);
        }
    }

    public List<String> getHeaders() {
        List<String> s = new ArrayList<>();

        // default headers
        s.add(defaultHeaders[0]);
        for (String header : Arrays.asList(defaultHeaders).subList(1, defaultHeaders.length))
            s.add(header);

        // user-provided other headers
        if (survey.otherHeaders != null)
            for (String header : survey.otherHeaders)
                s.add(header);
                defProcessors.add(new NotNull());

        // mturk-provided other headers
        Collections.sort(backendHeaders);
        for (String key : backendHeaders)
            s.add(key);
            defProcessors.add(new NotNull());

        //correlation
        if (survey.correlationMap != null && !survey.correlationMap.isEmpty())
            s.add(String.format("%s%s", sep, InputOutputKeys.CORRELATION));
            defProcessors.add(new NotNull());

        Runner.LOGGER.info("headers:" + s.toString());
        return s;
    }

    private CellProcessor[] getCellProcessors() {

        CellProcessor[] processors = new CellProcessor[defProcessors.size()];

        for (int i= 0 ; i < processors.length;i++){
            processors[i] = defProcessors.get(i);
        }
        // returns all of the cell processors (including the custom ones and the mturk backend ones
        return processors;
    }

    public void writeHeaders(CsvListWriter wr) throws IOException
    {
        List<String> headers = getHeaders();
        String[] headerArray = new String[headers.size()];
        headerArray = headers.toArray(headerArray);
        wr.writeHeader(headerArray);
    }

    public void writeResponse(SurveyResponse sr) throws IOException {
        // TODO: write response using cell processors.
        CsvListWriter writer = null;
        try {
            writer = new CsvListWriter(new FileWriter(outputFile) , CsvPreference.STANDARD_PREFERENCE);

            List<IQuestionResponse> responses=  sr.getAllResponses();

            for (IQuestionResponse response: responses){

                writer.write(response, getHeaders(), getCellProcessors());
            }
            sr.setRecorded(true);

        }catch (FileNotFoundException io ){
            throw new FileNotFoundException();
        }

    }

    

    private static String outputQuestionResponse(
            Survey survey,
            IQuestionResponse qr,
            SurveyResponse sr)
    {

        StringBuilder retval = new StringBuilder();

        // construct actual question text
        StringBuilder qtext = new StringBuilder();
        qtext.append(String.format("%s", qr.getQuestion().data.toString().replaceAll("\"", "\"\"")));
        qtext.insert(0, "\"");
        qtext.append("\"");

        assert qr.getOpts().size() > 0;

        // response options
        for (OptTuple opt : qr.getOpts()) {

            // construct actual option text
            String otext = "";
            if (opt.c instanceof HTMLDatum)
                otext = ((HTMLDatum) opt.c).data.toString();
            else if (opt.c instanceof StringDatum && ! opt.c.isEmpty())
                otext = ((StringDatum) opt.c).data.toString();
            otext = otext.replaceAll("\"", "\"\"");
            otext = "\"" + otext + "\"";

            //construct line of contents
            StringBuilder toWrite = new StringBuilder("%1$s");
            for (int i = 1 ; i < defaultHeaders.length ; i++)
                toWrite.append(String.format("%s%%%d$s", sep, i+1));
            retval.append(String.format(toWrite.toString()
                    , sr.getSrid()
                    , sr.getSrid()
                    , survey.sid
                    , qr.getQuestion().id
                    , qtext.toString()
                    , qr.getIndexSeen()
                    , opt.c.getId()
                    , otext
                    , opt.i));

            // add contents for user-defined headers
            if (survey.otherHeaders!=null && survey.otherHeaders.length > 0) {
                //retval.append(survey.otherHeaders[0]);
                for (int i = 0 ; i < survey.otherHeaders.length ; i++){
                    String header = survey.otherHeaders[i];
                    retval.append(String.format("%s\"%s\"", sep, qr.getQuestion().otherValues.get(header)));
                }
            }

            // add contents for system-defined headers
            Map<String, String> backendHeaders = sr.otherValues;
            if (!backendHeaders.isEmpty()) {
                List<String> keys = new ArrayList<String>(backendHeaders.keySet());
                Collections.sort(keys);
                for (String key : keys) {
                    retval.append(String.format("%s\"%s\"", sep, backendHeaders.get(key)));
                }
            }

            // add correlated info
            if (survey.correlationMap != null && !survey.correlationMap.isEmpty())
                retval.append(String.format("%s%s", sep, survey.getCorrelationLabel(qr.getQuestion())));

            retval.append(newline);

        }

        //retval.append(newline);
        return retval.toString();

    }

    public static String outputSurveyResponse(
            Survey survey,
            SurveyResponse sr)
    {

        StringBuilder retval = new StringBuilder();

        for (IQuestionResponse qr : sr.resultsAsMap().values())
            retval.append(outputQuestionResponse(survey, qr, sr));

        return retval.toString();
    }

    public static String outputSurveyResponses(Survey survey, List<SurveyResponse> surveyResponses) {

        StringBuilder retval = new StringBuilder();

        for (SurveyResponse sr : surveyResponses)
            retval.append(outputSurveyResponse(survey, sr));

        return retval.toString();

    }


}
