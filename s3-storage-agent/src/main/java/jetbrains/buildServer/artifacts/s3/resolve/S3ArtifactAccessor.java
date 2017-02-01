package jetbrains.buildServer.artifacts.s3.resolve;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import java.io.*;
import java.net.MalformedURLException;
import java.util.*;
import jetbrains.buildServer.agent.artifacts.AgentExternalArtifactHelper;
import jetbrains.buildServer.artifacts.ArtifactAccessor;
import jetbrains.buildServer.artifacts.ArtifactDependency;
import jetbrains.buildServer.artifacts.ExternalArtifact;
import jetbrains.buildServer.artifacts.ResolvingFailedException;
import jetbrains.buildServer.artifacts.s3.S3Constants;
import jetbrains.buildServer.util.FileUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Created by Nikita.Skvortsov
 * date: 26.01.2017.
 */
public class S3ArtifactAccessor implements ArtifactAccessor {

  @NotNull private final AmazonS3 myClient;
  @NotNull private final AgentExternalArtifactHelper myHelper;
  @NotNull private final Map<String, ExternalArtifact> myPathToArtifact = new HashMap<String, ExternalArtifact>();
  private volatile boolean isInterrupted;


  public S3ArtifactAccessor(@NotNull AmazonS3 client,
                            @NotNull AgentExternalArtifactHelper helper) {
    myClient = client;
    myHelper = helper;
  }

  @NotNull
  @Override
  public Collection<String> getArtifactSourcePathList(@NotNull final ArtifactDependency dependency) {
    myPathToArtifact.clear();
    Set<String> result = new HashSet<String>();
    Collection<ExternalArtifact> externalArtifacts = myHelper.getExternalArtifactsInfo(dependency);
    for (ExternalArtifact externalArtifact : externalArtifacts) {
      if (externalArtifact.getProperties().containsKey(S3Constants.S3_KEY_ATTR)) {
        myPathToArtifact.put(externalArtifact.getPath(), externalArtifact);
        result.add(externalArtifact.getPath());
      }
    }

    return result;
  }

  @Override
  public void downloadArtifact(@NotNull final ArtifactDependency dep, @NotNull final String sourcePath, @NotNull final File target) {
    final ExternalArtifact externalArtifact = myPathToArtifact.get(sourcePath);
    if (externalArtifact == null || externalArtifact.getUrl() == null) {
      throw new ResolvingFailedException("Failed to download [" + sourcePath + "] from [" + dep.toString() + "]");
    }
    try {
      downloadObject(externalArtifact.getProperties(), target);
    } catch (IOException e) {
      throw new ResolvingFailedException(e);
    }
  }

  private void downloadObject(@NotNull final Map<String, String> properties, @NotNull File file) throws IOException {
    try {
      final GetObjectRequest request = new GetObjectRequest(properties.get(S3Constants.S3_BUCKET_ATTR),
                                                            properties.get(S3Constants.S3_KEY_ATTR));
      final S3Object object = myClient.getObject(request);
      file.getParentFile().mkdirs();
      file.createNewFile();

      S3ObjectInputStream is = null;
      FileOutputStream os = null;
      try {
        is = object.getObjectContent();
        os = new FileOutputStream(file);
        final byte[] buffer = new byte[10 * 1024];
        int count;
        while ((count = is.read(buffer)) > 0) {
          if (isInterrupted) {
            break;
          }
          os.write(buffer, 0, count);
        }
      } finally {
        FileUtil.close(is);
        FileUtil.close(os);
      }

    } catch (MalformedURLException e) {
      throw new IOException(e);
    } finally {
      if (isInterrupted) {
        FileUtil.delete(file);
      }
    }
  }

  @Override
  public void interrupt() {
    isInterrupted = true;
  }
}