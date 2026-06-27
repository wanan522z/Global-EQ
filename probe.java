import java.lang.reflect.*;
public class Probe {
  public static void main(String[] args) throws Exception {
    Class<?> c = Class.forName("org.gradle.initialization.StartParameterBuildOptions$ProblemReportGenerationOption");
    System.out.println(c);
    for (Field f : c.getDeclaredFields()) {
      f.setAccessible(true);
      System.out.println(f.getName()+"="+f.get(null));
    }
  }
}
