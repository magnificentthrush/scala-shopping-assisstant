// Ye "page" hai jo route "/chat" pe dikhega
// Ye sirf ChatWidget ko wrap karta hai - agar future mein page-level cheez add karni ho to yahan hoga

import ChatWidget from "../../components/ChatWidget/ChatWidget";

export default function Chat() {
  return <ChatWidget />;
}