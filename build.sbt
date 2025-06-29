import play.sbt.routes.RoutesKeys
import uk.gov.hmrc.DefaultBuildSettings

ThisBuild / majorVersion := 0
ThisBuild / scalaVersion := "3.3.6"
ThisBuild / scalacOptions += "-Wconf:msg=Flag.*repeatedly:s"


lazy val microservice = Project("service-metrics", file("."))
  .enablePlugins(play.sbt.PlayScala, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin) //Required to prevent https://github.com/scalatest/scalatest/issues/1427
  .settings(
    PlayKeys.playDefaultPort :=  8859,
    libraryDependencies      ++= AppDependencies.compile ++ AppDependencies.test,
    // https://www.scala-lang.org/2021/01/12/configuring-and-suppressing-warnings.html
    // suppress warnings in generated routes files
    scalacOptions            += "-Wconf:src=routes/.*:s",
    javaOptions              += "-Xmx2G",
    RoutesKeys.routesImport  ++= Seq(
      "uk.gov.hmrc.servicemetrics.binders.Binders.given"
    , "uk.gov.hmrc.servicemetrics.model.Environment"
    , "uk.gov.hmrc.servicemetrics.config.AppConfig.LogMetricId"
    )
  )
  .settings(CodeCoverageSettings.settings: _*)

lazy val it =
  (project in file("it"))
    .enablePlugins(PlayScala)
    .dependsOn(microservice % "test->test")
    .settings(DefaultBuildSettings.itSettings())
