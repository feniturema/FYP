import Spinner from '../../components/common/Spinner';
import ProductCard from './ProductCard';
import type { SearchState } from './searchController';

// Results of GET /api/search for the Marketplace search box (docs/phases/P5a.md §6.6).
export default function SearchResults({ state }: { state: SearchState }) {
  if (state.status === 'loading') return <Spinner />;
  if (state.status === 'error') {
    return <p className="rounded-lg bg-red-50 px-4 py-2 text-sm text-red-700">{state.error ?? 'Search failed.'}</p>;
  }
  if (state.status === 'empty') {
    return <p className="text-sm text-gray-500">No products or listings match your search.</p>;
  }
  if (state.status !== 'results') return null;
  return (
    <section>
      <h2 className="mb-3 text-lg font-bold text-gray-800">Search results</h2>
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
        {state.hits.map((h) => (
          <ProductCard key={h.ref} title={h.title} price={h.price} imageUrl={h.imageUrl ?? undefined}
            badge={h.type === 'product' ? 'Official' : 'Second-hand'} subtitle={h.category ?? undefined}
            href={h.type === 'product' ? `/product/${h.id}` : `/item/${h.id}`} />
        ))}
      </div>
    </section>
  );
}
