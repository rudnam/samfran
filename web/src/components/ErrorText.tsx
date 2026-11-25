// What went wrong, in the page's red, read out as soon as it appears. Nothing
// when there's no message, so a page can always place it: {error} or ''.
export function ErrorText({ message }: { message?: string | null }) {
  if (!message) return null;
  return (
    <p role="alert" className="error">
      {message}
    </p>
  );
}
