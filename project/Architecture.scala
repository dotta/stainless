object Architecture {
  val osInf = Option(System.getProperty("os.name")).getOrElse("")

  val isUnix    = osInf.indexOf("nix") >= 0 || osInf.indexOf("nux") >= 0
  val isWindows = osInf.indexOf("Win") >= 0
  val isMac     = osInf.indexOf("Mac") >= 0

  val osName = if (isWindows) "win" else if (isMac) "mac" else "unix"
  val osArch = System.getProperty("sun.arch.data.model")
}
