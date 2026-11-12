import os
import time
import datetime as dt
import requests
from dotenv import load_dotenv

load_dotenv()

H = {
    "Accept": "application/vnd.github+json",
    "Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
    "X-GitHub-Api-Version": "2026-03-10",
}
API = "https://api.github.com"
session = requests.Session()


def get(url, params=None, attempts=6):
    """GET с повторами: при обрыве соединения ждём 2, 4, 8... секунд."""
    for i in range(1, attempts + 1):
        try:
            r = session.get(url, headers=H, params=params, timeout=(15, 30))
            if r.status_code in (429, 500, 502, 503, 504):
                raise requests.HTTPError(f"HTTP {r.status_code}", response=r)
            r.raise_for_status()
            return r
        except (requests.ConnectionError, requests.Timeout, requests.HTTPError) as e:
            code = getattr(getattr(e, "response", None), "status_code", None)
            if code is not None and code < 500 and code != 429:
                raise  # 401/403/404/422 повторять бессмысленно
            if i == attempts:
                raise
            pause = 2 ** i
            print(f"    сбой ({type(e).__name__}), попытка {i}/{attempts}, жду {pause} с")
            time.sleep(pause)


def star_history(repo):
    weeks, page = [], 1
    while page <= 100:
        r = get(f"{API}/repos/{repo}/stargazers/history",
                params={"per_page": 30, "page": page})
        chunk = r.json()
        weeks += chunk
        if len(chunk) < 30:
            break
        page += 1
    return weeks, r.headers.get("X-RateLimit-Remaining")


def stars_now(repo):
    return get(f"{API}/repos/{repo}").json()["stargazers_count"]


REPOS = [
    "psf/requests",
    "pallets/flask",
    "scikit-learn/scikit-learn",
    "pychess/pychess-variants",
    "Bush2021/edge_installer"
]

d = lambda ts: dt.datetime.fromtimestamp(ts, dt.timezone.utc).date()

for repo in REPOS:
    try:
        w, left = star_history(repo)
        now = stars_now(repo)
    except requests.RequestException as e:
        print(f"{repo}: ОШИБКА {type(e).__name__}: {str(e)[:150]}")
        continue
    print(f"{repo}: недель={len(w)}, сумма={sum(x['total'] for x in w)}, "
          f"сейчас={now}, с {d(w[-1]['week'])} по {d(w[0]['week'])}, "
          f"осталось запросов={left}")