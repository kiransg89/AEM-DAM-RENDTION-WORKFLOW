package com.example.core.services;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.WorkflowProcess;
import com.adobe.granite.workflow.metadata.MetaDataMap;
import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.Rendition;
import com.day.cq.dam.api.renditions.RenditionMaker;
import com.day.cq.dam.api.renditions.RenditionTemplate;
import com.day.cq.dam.commons.util.DamUtil;


@Component(service = {WorkflowProcess.class}, configurationPolicy = ConfigurationPolicy.OPTIONAL, property = {"process.label=Image Rendition Maker"})
public class ImageRendtionMaker implements WorkflowProcess {

  private static final Logger log = LoggerFactory.getLogger(ImageRendtionMaker.class);

  @Reference
  ExampleResourceResolverService ExampleResourceResolverService;

  @Reference
  protected RenditionMaker renditionMaker;

  public static enum Arguments {
    PROCESS_ARGS("PROCESS_ARGS"), DIMENSION("dimensions"), QUALITY("quality"), MIME_TYPE(
            "mimetypes"), KEEP_FORMAT_LIST("keepFormatList"), SKIP("skip");

    public final String legacyName;

    private Arguments(String legacyName) {
      this.legacyName = legacyName;
    }
  }

  public static class Config {
    public String[] dimensions;
    public int quality;
    public String[] mimeTypes;
    public String[] mimeTypesToKeep;
    public String[] skipMimeTypes;
  }

  public static class DimensionsConfig {
    public int width;
    public int height;
    public boolean doCenter;

    public DimensionsConfig(int width, int height, boolean doCenter) {
      this.width = width;
      this.height = height;
      this.doCenter = doCenter;
    }

  }

  @Override
  public void execute(WorkItem workItem, WorkflowSession wfSession, MetaDataMap metaDataMap)
          throws WorkflowException {
    final String payLoadPath = workItem.getWorkflowData().getPayload().toString();

    Resource resourceRes = ExampleResourceResolverService.getResourceResolver().getResource(payLoadPath);
    Asset asset = DamUtil.resolveToAsset(resourceRes);
    if (asset == null) {
      String wfPayload = workItem.getWorkflowData().getPayload().toString();
      String message = "execute: cannot create web enabled image,asset [{" + wfPayload + "}]"
              + " in payload doesn't exist for workflow [{" + workItem.getId() + "}].";
      throw new WorkflowException(message);
    }
    String processArgs = (String) metaDataMap.get(Arguments.PROCESS_ARGS.name(), String.class);
    Config config = parseLegacyConfig(processArgs);

    for (String mimeType : config.mimeTypes) {
      for (String dimension : config.dimensions) {
        log.debug("Inside loop, MimeTypes::{} and " + "Dimensions ::{} ",
                mimeType, dimension);
        DimensionsConfig dimensionConfig = parseDimensionsArguments(dimension);
        log.error( "Inside loop, Afert getting dimensions,Dimensions" + " - Width  ::{} and height ::{}", dimensionConfig.height, dimensionConfig.width);
        try {
          this.createWebEnabledImage(workItem, dimensionConfig, config, asset, mimeType, renditionMaker);
        } catch (RepositoryException e) {
          log.error( "Exception occured in createWebEnabledImages::{}", e);
        }
      }
    }
  }

  public void createWebEnabledImage(WorkItem workItem, DimensionsConfig dimensionConfig,
                                    Config config, Asset asset, String mimeType, RenditionMaker renditionMaker)
          throws RepositoryException {
    if (handleAsset(asset, config)) {
      asset.setBatchMode(true);
      RenditionTemplate template =
              renditionMaker.createWebRenditionTemplate(asset, dimensionConfig.width,
                      dimensionConfig.height, config.quality, mimeType, config.mimeTypesToKeep);
      renditionMaker.generateRenditions(asset, new RenditionTemplate[] {template});
    }
    Node assetNode = (Node) asset.adaptTo(Node.class);
    Node content = assetNode.getNode("jcr:content");

    String resolvedUser =
            (String) workItem.getWorkflowData().getMetaDataMap().get("userId", String.class);
    Rendition rendition = asset.getRendition("original");
    if (rendition != null) {
      String lastModified = (String) rendition.getProperties().get("jcr:lastModifiedBy");
      if (StringUtils.isNotBlank(lastModified)) {
        resolvedUser = lastModified;
      }
    }
    content.setProperty("jcr:lastModifiedBy", resolvedUser);
    content.setProperty("jcr:lastModified", Calendar.getInstance());
  }

  private Config parseLegacyConfig(String processArgs) {
    Config configs = new Config();
    String[] args = processArgs.split(",");
    configs.dimensions = getFirstValueFromArgs(args, Arguments.DIMENSION.legacyName, null).split(";");
    configs.mimeTypes = getFirstValueFromArgs(args, Arguments.MIME_TYPE.legacyName, "image/png").split(";");
    String keepFormat = getFirstValueFromArgs(args, Arguments.KEEP_FORMAT_LIST.legacyName,
            "image/pjpeg,image/jpeg,image/jpg,image/gif,image/png,image/x-png");
    configs.mimeTypesToKeep = keepFormat.split(",");
    String qualityStr = getFirstValueFromArgs(args, Arguments.QUALITY.legacyName, "100");
    configs.quality = Integer.valueOf(qualityStr).intValue();
    List<String> values = getValuesFromArgs(Arguments.SKIP.legacyName, args);
    configs.skipMimeTypes = ((String[]) values.toArray(new String[values.size()]));
    return configs;
  }

  private String getFirstValueFromArgs(String[] arguments, String key, String defaultValue) {
    List<String> values = getValuesFromArgs(key, arguments);
    if (values.size() > 0) {
      return (String) values.get(0);
    }
    return defaultValue;
  }

  protected List<String> getValuesFromArgs(String key, String[] arguments) {
    List<String> values = new ArrayList<>();
    for (String str : arguments) {
      if (str.startsWith(key + ":")) {
        String mt = str.substring((key + ":").length()).trim();
        values.add(mt);
      }
    }
    return values;
  }

  public static DimensionsConfig parseDimensionsArguments(String arg) {
    String str = arg.trim();
    if (str.contains("[")) {
      str = StringUtils.substringBetween(str, "[", "]");
      if (str == null) {
        log.debug("parseConfig: cannot parse width/height, " + "missing closing bracket '{}'.", arg);
        return null;
      }
    }
    String[] fragments = str.split(":");
    if (fragments.length >= 2) {
      try {
        Integer width = Integer.valueOf(fragments[0]);
        Integer height = Integer.valueOf(fragments[1]);

        boolean doCenter = false;
        if (fragments.length > 2) {
          doCenter = Boolean.valueOf(fragments[2]).booleanValue();
        }
        return new DimensionsConfig(width.intValue(), height.intValue(), doCenter);
      } catch (NumberFormatException e) {
        log.debug("parseConfig: cannot parse," + " invalid width/height specified in config '{}': ", str,e);
        return null;
      }
    }
    log.debug("parseConfig: cannot parse," + " insufficient arguments in config '{}'.", str);
    return null;
  }

  protected boolean handleAsset(Asset asset, Config config) {
    if ((asset == null) || (config.skipMimeTypes == null)) {
      return true;
    }
    String mimeType = asset.getMimeType();
    if (mimeType == null) {
      return true;
    }
    for (String val : config.skipMimeTypes) {
      if (mimeType.matches(val)) {
        log.debug("skipped for MIME type: " + mimeType);
        return false;
      }
    }
    return true;
  }

}
