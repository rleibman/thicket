package thicket.signals

private[signals] object ThreadGuardPlatform {
  /** JavaScript is single-threaded; there is nothing to guard against. */
  def owningThread: ThreadGuard = ThreadGuard.off
}
