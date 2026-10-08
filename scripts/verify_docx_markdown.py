"""Verify every Word text run occurs in the Markdown in the original order."""
from pathlib import Path
from zipfile import ZipFile
import xml.etree.ElementTree as ET

NS = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
for source in sorted(Path('docs').glob('*.docx')):
    markdown = source.with_suffix('.md').read_text(encoding='utf-8')
    markdown = markdown.replace('\\|', '|').replace('<br>', '\n')
    offset = 0
    count = 0
    with ZipFile(source) as archive:
        extra_parts = ('word/footnotes', 'word/endnotes', 'word/header', 'word/footer')
        names = ['word/document.xml'] + [
            name for name in archive.namelist()
            if name.startswith(extra_parts) and name.endswith('.xml')
        ]
        for name in names:
            for run in ET.fromstring(archive.read(name)).iter(NS + 't'):
                value = (run.text or '').strip()
                if not value:
                    continue
                found = markdown.find(value, offset)
                if found < 0:
                    raise AssertionError(f'{source.name}: missing text run #{count}')
                offset = found + len(value)
                count += 1
    print(f'{source.name}: {count} text runs preserved in order')
