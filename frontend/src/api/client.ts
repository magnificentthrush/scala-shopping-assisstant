/* 
apiFetch handles:

base URL
headers
JWT
JSON
response parsing
HTTP errors
401 handling
*/

// Base HTTP client — every API call in the app goes through this
const BASE_URL = import.meta.env.VITE_API_URL || "http://localhost:8080";

export class ApiError extends Error { //error class we built ourslef, extend Error(default by Javascript)
  code?: string;
  status: number;

  constructor(message: string, status: number, code?: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

function getToken(): string | null {
  return localStorage.getItem("token");
}

export async function apiFetch<T>(
  path: string,
  options: RequestInit = {}     //it is basically the complete httpreq body method, headers, body of http (depends upon who calls it)
): Promise<T> {
  const token = getToken();

  const headers: Record<string, string> = {     //extract headers from options
    ...(options.headers as Record<string, string>), //record is data structure key(string) -> val(string)
  };

  // Only set Content-Type when sending a body. Putting it on GET forces a
  // CORS preflight; Cask's OPTIONS handlers must then accept the same query
  // params as the real route (see AuthRoutes.verifyEmailOptions).

  //get wont have body, so there will be no Content-Type header in its request, and no CORS preflight will be triggered
  if (options.body != null && headers["Content-Type"] === undefined) {
    headers["Content-Type"] = "application/json";
  }

  const isPublicRoute = path.startsWith("/api/auth") || path === "/health";
  if (token && !isPublicRoute) {
    headers["Authorization"] = `Bearer ${token}`;
  }

  const res = await fetch(`${BASE_URL}${path}`, { // https//:localhost:8080/api/auth/register
    ...options,
    headers,
  });

  if (res.status === 204) {   //methods like "delete" dont have body so just return
    return {} as T;
  }

  const data = await res.json().catch(() => ({}));

  if (!res.ok) { // !(200 || 299)
    if (res.status === 401) {
      localStorage.removeItem("token");
      localStorage.removeItem("user");
    }
    throw new ApiError(data.error || "Something went wrong", res.status, data.code);
  }

  return data as T;
}

/* 
POST /api/users HTTP/1.1
Host: api.example.com
Content-Type: application/json
Authorization: Bearer abc123
Content-Length: 47

{"name": "Alice", "email": "alice@example.com"}

*/