package org.session.libsignal.service.api.messages;

import org.session.libsignal.libsignal.util.guava.Optional;
import org.session.libsignal.service.loki.protocol.meta.TTLUtilities;

public class SignalServiceTypingMessage {

  public enum Action {
    UNKNOWN, STARTED, STOPPED
  }

  private final Action           action;
  private final long             timestamp;

  public SignalServiceTypingMessage(Action action, long timestamp) {
    this.action    = action;
    this.timestamp = timestamp;
  }

  public Action getAction() {
    return action;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public boolean isTypingStarted() {
    return action == Action.STARTED;
  }

  public boolean isTypingStopped() {
    return action == Action.STOPPED;
  }

  public int getTTL() { return TTLUtilities.getTTL(TTLUtilities.MessageType.TypingIndicator); }
}
