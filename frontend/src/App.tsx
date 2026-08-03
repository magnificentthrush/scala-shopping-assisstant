// Main entry point — wraps the app with routing and authentication context

import { BrowserRouter, Routes, Route } from "react-router-dom";
import { AuthProvider } from "./context/AuthContext";
import ProtectedRoute from "./components/ProtectedRoute/ProtectedRoute";
import Navbar from "./components/Navbar/Navbar";
import Chat from "./pages/Chat/Chat";
import Login from "./pages/Login/Login";
import Signup from "./pages/Signup/Signup";

function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <a
          href="#main-content"
          className="fixed left-4 top-4 z-100 -translate-y-24 rounded-lg bg-white px-4 py-2 text-sm font-semibold text-gray-950 shadow-lg transition-transform focus-visible:translate-y-0"
        >
          Skip to Main Content
        </a>
        <Navbar />
        <main id="main-content">
          <Routes>
            {/* Chat requires login — unauthenticated users get redirected to /login */}
            <Route
              path="/"
              element={
                <ProtectedRoute>
                  <Chat />
                </ProtectedRoute>
              }
            />
            <Route path="/login" element={<Login />} />
            <Route path="/signup" element={<Signup />} />
          </Routes>
        </main>
      </BrowserRouter>
    </AuthProvider>
  );
}

export default App;