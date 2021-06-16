/**
 *
 */
package org.jenkinsci.plugins.scriptler.builder;

import com.thoughtworks.xstream.XStreamException;
import com.thoughtworks.xstream.converters.ConversionException;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Failure;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Project;
import hudson.security.Permission;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormApply;
import hudson.util.VersionNumber;
import hudson.util.XStream2;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.scriptler.Messages;
import org.jenkinsci.plugins.scriptler.ScriptlerManagement;
import org.jenkinsci.plugins.scriptler.ScriptlerPermissions;
import org.jenkinsci.plugins.scriptler.config.Parameter;
import org.jenkinsci.plugins.scriptler.config.Script;
import org.jenkinsci.plugins.scriptler.config.ScriptlerConfiguration;
import org.jenkinsci.plugins.scriptler.util.GroovyScript;
import org.jenkinsci.plugins.scriptler.util.ScriptHelper;
import org.jenkinsci.plugins.scriptler.util.UIHelper;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.bind.JavaScriptMethod;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import static hudson.util.QuotedStringTokenizer.quote;

/**
 * @author Dominik Bartholdi (imod)
 * 
 */
public class ScriptlerBuilder extends Builder implements Serializable {
    private static final AtomicInteger CURRENT_ID = new AtomicInteger();
    private static final long serialVersionUID = 1L;

    private final static Logger LOGGER = Logger.getLogger(ScriptlerBuilder.class.getName());

    // this is only used to identify the builder if a user without privileges modifies the job.
    private String builderId;
    private String scriptId;
    private boolean propagateParams = false;
    private Parameter[] parameters;

    public ScriptlerBuilder(String builderId, String scriptId, boolean propagateParams, Parameter[] parameters) {
        this.builderId = builderId;
        this.scriptId = scriptId;
        this.parameters = parameters;
        this.propagateParams = propagateParams;
    }

    private @Nonnull Map<String, String> checkGenericData() {
        Map<String, String> errors = new HashMap<>();

        Script script = ScriptHelper.getScript(scriptId, true);
        if (script != null) {
            if (!script.nonAdministerUsing) {
                this.scriptId = null;
                errors.put("scriptId", "The script is not allowed to be executed in a build, check its configuration!");
            }
        }

        checkPermission(errors);

        return errors;
    }

    private void checkPermission(@Nonnull Map<String, String> errors){
        if(Jenkins.get().hasPermission(Jenkins.RUN_SCRIPTS)){
            // user has right to add / edit Scripler steps
            return;
        }

        Project<?, ?> project = retrieveProjectUsingCurrentRequest();
        if(project != null){
            if(!hasSameScriptlerBuilderInProject(project, this)){
                if(StringUtils.isBlank(builderId)){
                    errors.put("builderId", "As the given builder does not have ID, it must be equals to one of the existing builder that does not have ID");
                }else{
                    errors.put("builderId", "The builderId must correspond to an existing builder of that project since the user does not have the rights to add/edit Scriptler step");
                }
            }
        }
        // else: we are not in a request context
    }

    /**
     * Must not be called inside XML processing since the modified data are not stored
     */
    private void generateBuilderIdIfRequired(){
        if(StringUtils.isBlank(builderId)){
            builderId = generateBuilderId();
        }
    }

    private Object readResolve() {
        return this;
    }

    private boolean hasSameScriptlerBuilderInProject(@Nonnull Project<?, ?> project, @Nonnull ScriptlerBuilder targetBuilder){
        List<ScriptlerBuilder> allScriptlerBuilders = _getAllScriptlerBuildersFromProject(project);
        for (ScriptlerBuilder builder : allScriptlerBuilders) {
            if(targetBuilder.equals(builder)){
                return true;
            }
        }

        return false;
    }

    private @Nonnull List<ScriptlerBuilder> _getAllScriptlerBuildersFromProject(@Nonnull Project<?, ?> project){
        return project.getBuildersList().getAll(ScriptlerBuilder.class);
    }

    private @CheckForNull Project<?, ?> retrieveProjectUsingCurrentRequest(){
        StaplerRequest currentRequest = Stapler.getCurrentRequest();
        if(currentRequest != null) {
            Project<?, ?> project = Stapler.getCurrentRequest().findAncestorObject(Project.class);
            if (project != null) {
                return project;
            }
        }

        // we are not in a request or manipulating a builder outside of a project
        return null;
    }

    public String getScriptId() {
        return scriptId;
    }

    public Parameter[] getParameters() {
        return parameters;
    }

    public String getBuilderId() {
        return builderId;
    }

    public boolean isPropagateParams() {
        return propagateParams;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
        final Script script = ScriptHelper.getScript(scriptId, true);
    
        if (script == null) {
            if (StringUtils.isBlank(scriptId)) {
                LOGGER.log(Level.WARNING, "The script id was blank for the build {0}:{1}", new Object[]{build.getProject().getName(), build.getDisplayName()});
                listener.getLogger().println(Messages.scriptNotDefined());
            } else {
                LOGGER.log(Level.WARNING, "The source corresponding to the scriptId {0} was not found (missing file ?) for the build {1}:{2}",
                        new Object[]{scriptId, build.getProject().getName(), build.getDisplayName()}
                        );
                listener.getLogger().println(Messages.scriptNotFound(scriptId));
            }
            return false;
        } 
        
        boolean isOk = false;
        if (!script.nonAdministerUsing) {
            listener.getLogger().println(Messages.scriptNotUsableInBuildStep(script.getName()));
            LOGGER.log(Level.WARNING, "The script [{0} ({1})] is not allowed to be executed in a build, check its configuration. It concerns the build [{2}:{3}]", 
                    new Object[]{script.getName(), script.getId(), build.getProject().getName(), build.getDisplayName()}
                    );
            return false;
        }
        
        if(!ScriptHelper.isApproved(script.script)){
            listener.getLogger().println(Messages.scriptNotApprovedYet(script.getName()));
            LOGGER.log(Level.WARNING, "The script [{0} ({1})] is not approved yet, consider asking your administrator to approve it. It concerns the build [{2}:{3}]", 
                    new Object[]{script.getName(), script.getId(), build.getProject().getName(), build.getDisplayName()}
                    );
            return false;
        }
        
        try {

            // expand the parameters before passing these to the execution, this is to allow any token macro to resolve parameter values
            List<Parameter> expandedParams = new LinkedList<>();

            if (propagateParams) {
                final ParametersAction paramsAction = build.getAction(ParametersAction.class);
                if (paramsAction == null) {
                    listener.getLogger().println(Messages.no_parameters_defined());
                } else {
                    final List<ParameterValue> jobParams = paramsAction.getParameters();
                    for (ParameterValue parameterValue : jobParams) {
                        // pass the params to the token expander in a way that these get expanded by environment variables (params are also environment variables)
                        expandedParams.add(new Parameter(parameterValue.getName(), TokenMacro.expandAll(build, listener, "${" + parameterValue.getName() + "}", false, null)));
                    }
                }
            }
            for (Parameter parameter : parameters) {
                expandedParams.add(new Parameter(parameter.getName(), TokenMacro.expandAll(build, listener, parameter.getValue())));
            }
            final Object output;
            if (script.onlyMaster) {
                // When run on master, make build, launcher, listener available to script
                output = FilePath.localChannel.call(new GroovyScript(script.script, expandedParams.toArray(new Parameter[expandedParams.size()]), true, listener, launcher, build));
            } else {
                output = launcher.getChannel().call(new GroovyScript(script.script, expandedParams.toArray(new Parameter[expandedParams.size()]), true, listener));
            }
            if (output instanceof Boolean && Boolean.FALSE.equals(output)) {
                isOk = false;
            } else {
                isOk = true;
            }
        } catch (Exception e) {
            listener.getLogger().println(Messages.scriptExecutionFailed(scriptId) + " - " + e.getMessage());
            e.printStackTrace(listener.getLogger());
        }

        return isOk;
    }

    private static String generateBuilderId(){
        return System.currentTimeMillis() + "_" + CURRENT_ID.addAndGet(1);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        ScriptlerBuilder that = (ScriptlerBuilder) o;

        if (propagateParams != that.propagateParams)
            return false;
        if (builderId != null ? !builderId.equals(that.builderId) : that.builderId != null)
            return false;
        if (scriptId != null ? !scriptId.equals(that.scriptId) : that.scriptId != null)
            return false;

        return Arrays.equals(parameters, that.parameters);
    }

    @Override
    public int hashCode() {
        int result = builderId != null ? builderId.hashCode() : 0;
        result = 31 * result + (scriptId != null ? scriptId.hashCode() : 0);
        result = 31 * result + (propagateParams ? 1 : 0);
        result = 31 * result + Arrays.hashCode(parameters);
        return result;
    }

    // Overridden for better type safety.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    /**
     * Automatically registered by {@link XStream2.AssociatedConverterImpl#findConverter(Class)}
     * Process the class regularly but add a check after that
     */
    public static final class ConverterImpl extends XStream2.PassthruConverter<ScriptlerBuilder>{
        public ConverterImpl(XStream2 xstream) {
            super(xstream);
        }

        @Override
        public boolean canConvert(Class type) {
            return super.canConvert(type);
        }

        @Override
        protected void callback(ScriptlerBuilder obj, UnmarshallingContext context) {
            Map<String, String> errors = obj.checkGenericData();

            if(!errors.isEmpty()){
                ConversionException conversionException = new ConversionException("Validation failed");
                for (Map.Entry<String, String> error : errors.entrySet()) {
                    conversionException.add(error.getKey(), error.getValue());
                }

                //TODO when upgrading to 1.625+, we could remove this code
                if(!Jenkins.getVersion().isOlderThan(new VersionNumber("1.625"))){
                    XStreamException criticalXStreamException = buildCriticalXStreamException(conversionException);
                    throw criticalXStreamException;
                }

                // go directly to XmlFile#unmarshal catch
                throw new Error(conversionException);
            }
        }

        @SuppressWarnings("unchecked")
        private @CheckForNull XStreamException buildCriticalXStreamException(ConversionException conversionException){
            XStreamException xStreamException = null;
            try {
                Class<? extends XStreamException> criticalXStreamExceptionClass = (Class<? extends XStreamException>) this.getClass().getClassLoader().loadClass("jenkins.util.xstream.CriticalXStreamException");
                Constructor<? extends XStreamException> constructor = criticalXStreamExceptionClass.getConstructor(XStreamException.class);
                xStreamException = constructor.newInstance(conversionException);
            } catch (ClassNotFoundException e) {
                LOGGER.log(Level.SEVERE, "The CriticalXStreamException was not found using jenkins.util.xstream.CriticalXStreamException", e);
            } catch (NoSuchMethodException e) {
                LOGGER.log(Level.SEVERE, "The CriticalXStreamException does not have the expected constructor accepting only XStreamException as parameter", e);
            } catch (IllegalAccessException e) {
                LOGGER.log(Level.SEVERE, "Problem during invocation of constructor", e);
            } catch (InstantiationException e) {
                LOGGER.log(Level.SEVERE, "Problem during invocation of constructor", e);
            } catch (InvocationTargetException e) {
                LOGGER.log(Level.SEVERE, "Problem during invocation of constructor", e);
            }

            return xStreamException;
        }
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {


        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return Jenkins.get().hasPermission(ScriptlerPermissions.RUN_SCRIPTS);
        }

        @Override
        public String getDisplayName() {
            return Messages.builder_name();
        }

        // used by Jelly views
        public Permission getRequiredPermission() {
            return ScriptlerPermissions.RUN_SCRIPTS;
        }

        @Override
        public ScriptlerBuilder newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            ScriptlerBuilder builder = null;
            String builderId = formData.optString("builderId");
            String id = formData.optString("scriptlerScriptId");

            if (StringUtils.isNotBlank(id)) {
                boolean inPropagateParams = formData.getBoolean("propagateParams");
                Parameter[] params = null;
                try {
                    params = UIHelper.extractParameters(formData);
                } catch (ServletException e) {
                    throw new FormException(Messages.parameterExtractionFailed(), "parameters");
                }
                builder = new ScriptlerBuilder(builderId, id, inPropagateParams, params);
            }

            if(builder != null){
                Map<String, String> errors = builder.checkGenericData();
                if(!errors.isEmpty()){
                    throw new MultipleErrorFormValidation(errors);
                }
            }

            if (builder == null) {
                builder = new ScriptlerBuilder(builderId, null, false, null);
            }

            builder.generateBuilderIdIfRequired();

            return builder;
        }

        public List<Script> getScripts() {
            // TODO currently only script for RUN_SCRIPT permissions are returned?
            Set<Script> scripts = getConfig().getScripts();
            List<Script> scriptsForBuilder = new ArrayList<>();
            for (Script script : scripts) {
                if (script.nonAdministerUsing) {
                    scriptsForBuilder.add(script);
                }
            }
            Collections.sort(scriptsForBuilder, Script.COMPARATOR_BY_NAME);
            return scriptsForBuilder;
        }

        private ScriptlerManagement getScriptler() {
            return ExtensionList.lookupSingleton(ScriptlerManagement.class);
        }

        private ScriptlerConfiguration getConfig() {
            return getScriptler().getConfiguration();
        }

        /**
         * gets the argument description to be displayed on the screen when selecting a config in the dropdown
         * 
         * @param scriptlerScriptId
         *            the config id to get the arguments description for
         * @return the description
         */
        @JavaScriptMethod
        public JSONArray getParameters(String scriptlerScriptId) {
            final Script script = getConfig().getScriptById(scriptlerScriptId);
            if (script != null && script.getParameters() != null) {
                return JSONArray.fromObject(script.getParameters());
            }
            return null;
        }
    }

    /**
     * Notify the user with multiple message about the validation that failed
     */
    private static class MultipleErrorFormValidation extends RuntimeException implements HttpResponse {
        private Map<String, String> fieldToMessage = new HashMap<>();

        public MultipleErrorFormValidation(Map<String, String> fieldToMessage) {
            this.fieldToMessage = fieldToMessage;
        }

        private String getAggregatedMessage(){
            List<String> errorMessageList = new ArrayList<>();
            for (Map.Entry<String, String> error : fieldToMessage.entrySet()) {
                errorMessageList.add(buildMessageForField(error.getKey(), error.getValue()));
            }
            return StringUtils.join(errorMessageList, ", ");
        }

        private String buildMessageForField(String fieldName, String fieldMessage){
            return fieldName + ": " + fieldMessage;
        }

        @Override
        public void generateResponse(StaplerRequest req, StaplerResponse rsp, Object node) throws IOException, ServletException {
            if (FormApply.isApply(req)) {
                StringBuilder scriptBuilder = new StringBuilder();
                for (Map.Entry<String, String> error : fieldToMessage.entrySet()) {
                    String errorMessage = buildMessageForField(error.getKey(), error.getValue());
                    scriptBuilder
                            .append("notificationBar.show(")
                            .append(quote(errorMessage))
                            .append(",notificationBar.ERROR)")
                    ;
                }

                FormApply.applyResponse(scriptBuilder.toString())
                        .generateResponse(req, rsp, node);
            } else {
                new Failure(getAggregatedMessage()).generateResponse(req,rsp,node);
            }
        }
    }
}
