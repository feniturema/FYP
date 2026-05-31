import { useEffect, useState } from 'react';
import { seckillApi } from '../../services/api';
import type { SeckillEvent } from '../../types';
import SeckillCard from './SeckillCard';
import Spinner from '../../components/common/Spinner';

export default function SeckillList() {
  const [events, setEvents] = useState<SeckillEvent[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    seckillApi.events().then(setEvents).finally(() => setLoading(false));
  }, []);

  return (
    <div>
      <h1 className="mb-1 text-2xl font-extrabold text-ukm-700">⚡ Flash Sale (SecKill)</h1>
      <p className="mb-6 text-sm text-gray-500">Limited stock, one per student. Be quick!</p>
      {loading ? <Spinner /> : (
        <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {events.map((e) => <SeckillCard key={e.id} event={e} />)}
          {events.length === 0 && <p className="text-sm text-gray-500">No flash sales scheduled.</p>}
        </div>
      )}
    </div>
  );
}
