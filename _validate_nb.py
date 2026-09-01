# -*- coding: utf-8 -*-
import ast
import io
import json

nb = json.load(io.open(r"ai\mazeward_colab_notebook.ipynb", encoding="utf-8"))
n = 0
for i, c in enumerate(nb["cells"]):
    if c.get("cell_type") != "code":
        continue
    src = "".join(c.get("source", []))
    n += 1
    try:
        ast.parse(src)
    except SyntaxError as e:
        print(f"cell {i}: SyntaxError: {e.msg} (line {e.lineno})")
        raise
print(f"OK cells={len(nb['cells'])} code_cells={n}")