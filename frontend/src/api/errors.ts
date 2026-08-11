import { ApiError } from "./client";

export function getErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    switch (err.code) {
      case "REJECTED":
        return "I can't help with that request. Please ask about shopping or products.";
      case "BLANK_TITLE":
        return "Title cannot be blank.";
      case "SESSION_NOT_FOUND":
        return "Session expired. Please start a new chat.";
      case "ASSISTANT_FAILED":
        return "Assistant failed to respond. Please try again.";
      case "UPSTREAM_UNAVAILABLE":
        return "Service temporarily unavailable. Please try again.";
      default:
        return err.message;
    }
  }
  return "Something went wrong. Please try again.";
}
