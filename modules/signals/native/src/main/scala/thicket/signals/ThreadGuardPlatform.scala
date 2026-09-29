package thicket.signals

private[signals] object ThreadGuardPlatform {
  def owningThread: ThreadGuard = new ThreadGuard {
    private var owner: Thread | Null = null
    def check(op: String): Unit = {
      val current = Thread.currentThread()
      val o       = owner
      if o == null then owner = current
      else if o.nn ne current then
        throw new IllegalStateException(
          s"$op was called on thread '${current.getName}' but the signal graph is owned by " +
            s"'${o.nn.getName}'. Signals are single-threaded; marshal onto the UI thread " +
            "(see docs/07 §7.7)."
        )
    }
  }
}
