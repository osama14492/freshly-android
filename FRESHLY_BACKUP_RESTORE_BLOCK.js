  // FRESHLY complete local backup: include cashier, menu and offline Firebase cache/queue.
  const FRESHLY_BACKUP_APP='FRESHLY_POS_BACKUP';
  function createFreshlyBackupObject(){
    const all={};
    for(let i=0;i<localStorage.length;i++){
      const key=localStorage.key(i); if(key===null) continue;
      const value=localStorage.getItem(key); if(value!==null) all[key]=value;
    }
    all.fp_items_v3=JSON.stringify(state.items);
    return {app:FRESHLY_BACKUP_APP,version:2,createdAt:new Date().toISOString(),storage:'localStorage',entryCount:Object.keys(all).length,data:all};
  }
  function downloadFreshlyBackup(obj,prefix){
    const blob=new Blob([JSON.stringify(obj,null,2)],{type:'application/json'});
    const url=URL.createObjectURL(blob); const a=document.createElement('a');
    a.href=url; a.download=(prefix||'FRESHLY_POS_BACKUP_')+new Date().toISOString().slice(0,10)+'.json';
    document.body.appendChild(a); a.click(); a.remove(); setTimeout(()=>URL.revokeObjectURL(url),1500);
  }
  document.getElementById('exportBackupBtn').addEventListener('click',()=>{
    try {
      const backup=createFreshlyBackupObject();
      const fileName='FRESHLY_POS_BACKUP_'+new Date().toISOString().slice(0,10)+'.json';
      const json=JSON.stringify(backup,null,2);
      if(window.FRESHLYNative && typeof window.FRESHLYNative.saveBackup==='function') {
        const result=window.FRESHLYNative.saveBackup(json,fileName);
        if(result==='OK') alert('تم حفظ النسخة الاحتياطية على الجهاز داخل Downloads/FRESHLY.');
        else { downloadFreshlyBackup(backup,'FRESHLY_POS_BACKUP_'); alert('تعذر الحفظ المباشر. تم بدء تنزيل ملف النسخة الاحتياطية.'); }
      } else {
        downloadFreshlyBackup(backup,'FRESHLY_POS_BACKUP_');
        alert('تم بدء تنزيل ملف النسخة الاحتياطية. احفظه في مكان آمن.');
      }
    } catch(err){ console.error(err); alert('تعذر إنشاء النسخة الاحتياطية. لم يتم تغيير أي بيانات.'); }
  });
  document.getElementById('importBackupBtn').addEventListener('click',()=>document.getElementById('backupFileInput').click());
  document.getElementById('backupFileInput').addEventListener('change',e=>{
    const f=e.target.files&&e.target.files[0]; if(!f)return;
    const reader=new FileReader();
    reader.onload=()=>{
      try {
        const b=JSON.parse(reader.result);
        if(!b || b.app!==FRESHLY_BACKUP_APP || ![1,2].includes(Number(b.version)) ||
           !b.data || typeof b.data!=='object' || Array.isArray(b.data)) throw new Error('invalid backup header');
        const entries=Object.entries(b.data);
        if(!entries.length || entries.length>10000 ||
           entries.some(([k,v])=>typeof k!=='string'||!k.length||typeof v!=='string')) throw new Error('invalid backup entries');
        if(b.version>=2 && Number(b.entryCount)!==entries.length) throw new Error('backup count mismatch');
        for(const k of ['fp_items_v3','fp_sales_v3','fp_held_v3','fp_custody_v3','fp_expenses_v3',
          'fp_company_settings_v1','fp_public_items_v6','fp_public_cats_v6','fp_public_contact_v6',
          'freshly_rtdb_local_cache_v1','freshly_rtdb_write_queue_v1']){
          if(Object.prototype.hasOwnProperty.call(b.data,k)) JSON.parse(b.data[k]);
        }
        if(!confirm('تم فحص ملف النسخة الاحتياطية. سيتم حفظ نسخة أمان داخل الذاكرة الحالية ثم استعادة بيانات الملف. هل تريد المتابعة؟')) return;
        const before={};
        for(let i=0;i<localStorage.length;i++){const k=localStorage.key(i);if(k!==null)before[k]=localStorage.getItem(k);}
        try {
          for(const [k,v] of entries) localStorage.setItem(k,v);
        } catch(writeErr) {
          for(let i=localStorage.length-1;i>=0;i--){
            const k=localStorage.key(i);
            if(k!==null && !Object.prototype.hasOwnProperty.call(before,k)) localStorage.removeItem(k);
          }
          Object.entries(before).forEach(([k,v])=>localStorage.setItem(k,v));
          throw writeErr;
        }
        alert('تمت استعادة النسخة الاحتياطية بنجاح. سيتم إعادة تحميل التطبيق.');
        location.reload();
      } catch(err){
        console.error('FRESHLY restore rejected',err);
        alert('تعذر استعادة النسخة: الملف غير صالح أو مساحة التخزين غير كافية. لم يتم اعتماد أي تغيير.');
      } finally { e.target.value=''; }
    };
    reader.onerror=()=>{alert('تعذر قراءة ملف النسخة الاحتياطية.');e.target.value='';};
    reader.readAsText(f);
  });
