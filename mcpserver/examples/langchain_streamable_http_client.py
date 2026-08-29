"""使用 LangChain MCP adapter 连接 Streamable HTTP MCP Server。"""

from __future__ import annotations

import asyncio
import os

from dotenv import load_dotenv
from langchain_mcp_adapters.client import MultiServerMCPClient


async def main() -> None:
    """读取 MCP HTTP 地址并打印 LangChain 可发现的 tools。"""

    load_dotenv()
    host = os.getenv("MCP_HTTP_HOST", "127.0.0.1")
    port = os.getenv("MCP_HTTP_PORT", "8820")
    path = os.getenv("MCP_HTTP_PATH", "/mcp")
    url = f"http://{host}:{port}{path}"

    client = MultiServerMCPClient(
        {
            "datadev": {
                "transport": "streamable_http",
                "url": url,
            }
        }
    )
    tools = await client.get_tools()

    print(f"MCP URL: {url}")
    print("Tools:")
    for tool in tools:
        print(f"- {tool.name}")


if __name__ == "__main__":
    asyncio.run(main())
